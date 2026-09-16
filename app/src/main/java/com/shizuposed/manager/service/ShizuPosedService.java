package com.shizuposed.manager.service;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Process;

import androidx.core.app.NotificationCompat;

import com.google.gson.Gson;
import com.shizuposed.manager.R;
import com.shizuposed.manager.ShizukuHelper;
import com.shizuposed.manager.ShizuPosedManagerApp;
import com.shizuposed.manager.core.ModuleLoader;
import com.shizuposed.manager.core.ProcessMonitor;
import com.shizuposed.manager.model.ModuleInfo;
import com.shizuposed.manager.utils.Logger;
import com.shizuposed.manager.utils.ShellUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Foreground service that owns the ShizuPosed lifecycle.
 *
 * FGS CONTRACT
 * ------------
 * Android requires that any service started via
 * Context.startForegroundService() calls Service.startForeground()
 * within 5 seconds, on the main thread, or the process is killed
 * with ForegroundServiceDidNotStartInTimeException.
 *
 * This service therefore calls startForeground() as the very first
 * statement in onCreate(), before any logging, authorization check,
 * binder lookup, or file I/O.
 *
 * AUTHORIZATION CONTRACT
 * ----------------------
 * The service refuses to do work without an authorized
 * Shizuku/Sui, but it does NOT refuse to be a foreground service.
 *
 * NATIVE PAYLOAD
 * --------------
 * Three files travel to the shell side:
 *   • XposedHook.dex       — the hook payload
 *   • libamiru.so          — Amiru native engine
 *   • libshizuposed.so     — existing shared-dispatcher engine
 *
 * They are staged from APK-internal locations to external app
 * storage (shell-readable) and then pushed to the shell's own
 * files dir via Shizuku. XposedHook.dex comes from assets;
 * the .so files come from the app's nativeLibraryDir where the
 * platform placed them at install time.
 */
public class ShizuPosedService extends Service {
    private static final String CHANNEL_ID = "shizuposed_service";
    private static final String CHANNEL_NAME = "ShizuPosed Service";
    private static final int NOTIFICATION_ID = 1001;

    public static final String ACTION_REPUSH_MODULES =
        "com.shizuposed.manager.ACTION_REPUSH_MODULES";
    public static final String ACTION_LAUNCH_APP =
        "com.shizuposed.manager.ACTION_LAUNCH_APP";
    public static final String EXTRA_LAUNCH_PACKAGE = "package";

    private static final String SHELL_FILES_DIR   = "/data/user/0/com.android.shell/files";
    private static final String SHELL_DEX_PATH    = SHELL_FILES_DIR + "/XposedHook.dex";
    private static final String SHELL_LIB_DIR     = SHELL_FILES_DIR + "/libs";
    private static final String SHELL_AMIRU_PATH  = SHELL_LIB_DIR + "/libamiru.so";
    private static final String SHELL_SZP_SO_PATH = SHELL_LIB_DIR + "/libshizuposed.so";
    private static final String SHELL_CACHE_DIR   = SHELL_FILES_DIR + "/.syscall_cache";
    private static final String SHELL_MODULES_DIR = SHELL_CACHE_DIR + "/modules";
    private static final String SHELL_HOOKED_DIR  = SHELL_CACHE_DIR + "/hooked";

    /** Names of the native libraries that travel to the shell side. */
    private static final String LIB_AMIRU       = "libamiru.so";
    private static final String LIB_SHIZUPOSED  = "libshizuposed.so";

    private static final boolean REQUIRE_AUTHORIZATION = true;

    private String localStagingDexPath;
    private String localStagingModulesDir;
    private String localStagingLibDir;
    private String xposedHookDexPath;

    private static volatile boolean isServiceRunning = false;

    private Logger logger;
    private HandlerThread workerThread;
    private Handler workerHandler;
    private ProcessMonitor processMonitor;
    private ModuleLoader moduleLoader;
    private ShizukuHelper shizukuHelper;
    private final Gson gson = new Gson();

    private final AtomicBoolean isRunning = new AtomicBoolean(false);
    private final AtomicBoolean xposedHookStarted = new AtomicBoolean(false);
    private final AtomicBoolean dexDeployed = new AtomicBoolean(false);
    private final AtomicBoolean libsDeployed = new AtomicBoolean(false);

    private final AtomicBoolean onAuthorizedDispatched = new AtomicBoolean(false);
    private final AtomicBoolean foregroundStarted = new AtomicBoolean(false);

    // ═════════════════════════════════════════════════════════════
    // AUTHORIZATION GATE
    // ═════════════════════════════════════════════════════════════

    private boolean isAuthorizedNow() {
        try {
            ShizukuHelper helper = ShizukuHelper.getInstance(this);
            return helper.isAvailable() && helper.isAuthorized();
        } catch (Throwable t) {
            if (logger != null) {
                logger.e("isAuthorizedNow failed: " + t.getMessage());
            }
            return false;
        }
    }

    // ═════════════════════════════════════════════════════════════
    // LIFECYCLE
    // ═════════════════════════════════════════════════════════════

    @Override
    public void onCreate() {
        super.onCreate();
        isServiceRunning = true;

        // ═══════════════════════════════════════════════════════════
        // STEP 1 — FGS CONTRACT (must be first)
        // ═══════════════════════════════════════════════════════════

        createNotificationChannel();

        try {
            startForeground(NOTIFICATION_ID, buildNotification("Service starting"));
            foregroundStarted.set(true);
        } catch (Throwable t) {
            android.util.Log.e("ShizuPosedService",
                "startForeground failed in onCreate", t);
            isServiceRunning = false;
            stopSelf();
            return;
        }

        // ═══════════════════════════════════════════════════════════
        // STEP 2 — Everything else
        // ═══════════════════════════════════════════════════════════

        logger = Logger.getInstance(this);

        if (REQUIRE_AUTHORIZATION && !isAuthorizedNow()) {
            logger.w("⛔ ShizuPosedService.onCreate: Shizuku not authorized — "
                    + "stopping self (FGS contract already satisfied).");
            isServiceRunning = false;
            stopSelf();
            return;
        }

        logger.i("🚀 ShizuPosedService creating...");

        workerThread = new HandlerThread("ShizuPosedWorker",
                Process.THREAD_PRIORITY_BACKGROUND);
        workerThread.start();
        workerHandler = new Handler(workerThread.getLooper());

        workerHandler.post(() -> {
            try {
                File externalDir = getExternalFilesDir(null);
                if (externalDir == null) {
                    logger.w("⚠️ External app dir unavailable, falling back to internal");
                    externalDir = getFilesDir();
                }
                if (!externalDir.exists()) externalDir.mkdirs();

                localStagingDexPath =
                    new File(externalDir, "XposedHook.dex").getAbsolutePath();
                localStagingModulesDir =
                    new File(externalDir, "modules").getAbsolutePath();
                new File(localStagingModulesDir).mkdirs();

                localStagingLibDir =
                    new File(externalDir, "libs").getAbsolutePath();
                new File(localStagingLibDir).mkdirs();

                xposedHookDexPath = SHELL_DEX_PATH;

                logger.i("Local staging dex:  " + localStagingDexPath);
                logger.i("Local staging mods: " + localStagingModulesDir);
                logger.i("Local staging libs: " + localStagingLibDir);
                logger.i("Target shell dex:   " + xposedHookDexPath);
                logger.i("Target shell libs:  " + SHELL_LIB_DIR);

                initComponents();
                startServiceInternal();

                logger.i("✅ ShizuPosedService created");
            } catch (Throwable t) {
                logger.e("❌ onCreate worker failed: " + t.getMessage());
            }
        });
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // ═══════════════════════════════════════════════════════════
        // FGS CONTRACT — re-assert on every start
        // ═══════════════════════════════════════════════════════════

        try {
            startForeground(NOTIFICATION_ID, buildNotification("Service running"));
            foregroundStarted.set(true);
        } catch (Throwable t) {
            android.util.Log.e("ShizuPosedService",
                "startForeground failed in onStartCommand", t);
        }

        // ═══════════════════════════════════════════════════════════
        // AUTHORIZATION GATE — after the FGS contract
        // ═══════════════════════════════════════════════════════════

        if (REQUIRE_AUTHORIZATION && !isAuthorizedNow()) {
            if (logger != null) {
                logger.w("⛔ onStartCommand: not authorized — stopping self");
            }
            isServiceRunning = false;
            stopSelf(startId);
            return START_NOT_STICKY;
        }

        isServiceRunning = true;

        if (workerHandler == null) {
            return START_STICKY;
        }

        if (intent != null) {
            String action = intent.getAction();

            if (ShizuPosedManagerApp.ACTION_SHIZUKU_AUTHORIZED.equals(action)) {
                logger.i("📨 ACTION_SHIZUKU_AUTHORIZED received");
                onShizukuAuthorized();

            } else if (ACTION_LAUNCH_APP.equals(action)) {
                String pkg = intent.getStringExtra(EXTRA_LAUNCH_PACKAGE);
                if (pkg != null) {
                    final String target = pkg;
                    logger.i("📨 ACTION_LAUNCH_APP received for " + target);
                    workerHandler.post(() -> launchAppUnderShizuPosed(target));
                }

            } else if (ACTION_REPUSH_MODULES.equals(action)) {
                logger.i("📨 ACTION_REPUSH_MODULES received");
                workerHandler.post(() -> {
                    if (ensurePayloadReady()) {
                        pushModulesToShellDir(moduleLoader.loadModules());
                    }
                });
            }
        }

        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        if (logger != null) logger.i("ShizuPosedService destroying...");
        isRunning.set(false);
        isServiceRunning = false;
        if (processMonitor != null) processMonitor.stopMonitoring();
        if (workerThread != null) workerThread.quitSafely();

        if (foregroundStarted.getAndSet(false)) {
            try {
                stopForeground(STOP_FOREGROUND_REMOVE);
            } catch (Throwable ignored) {}
        }

        onAuthorizedDispatched.set(false);

        try {
            ShizuPosedManagerApp app = ShizuPosedManagerApp.getInstance();
            if (app != null) app.onServiceDestroyed();
        } catch (Throwable ignored) {}

        super.onDestroy();
    }

    // ═════════════════════════════════════════════════════════════
    // NOTIFICATION
    // ═════════════════════════════════════════════════════════════

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            android.app.NotificationChannel channel = new android.app.NotificationChannel(
                CHANNEL_ID, CHANNEL_NAME,
                android.app.NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("ShizuPosed Manager service");
            channel.setShowBadge(false);
            android.app.NotificationManager manager =
                getSystemService(android.app.NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification(String text) {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("ShizuPosed Manager")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setAutoCancel(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build();
    }

    private void updateNotification(String status) {
        if (!foregroundStarted.get()) return;
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) manager.notify(NOTIFICATION_ID, buildNotification(status));
    }

    // ═════════════════════════════════════════════════════════════
    // COMPONENT INIT
    // ═════════════════════════════════════════════════════════════

    private void initComponents() {
        shizukuHelper = ShizukuHelper.getInstance(this);
        moduleLoader = ModuleLoader.getInstance(this);
        processMonitor = ProcessMonitor.getInstance(this);
        processMonitor.setShizuPosedService(this);
        logger.i("Components initialized (payload deployment deferred until Shizuku ready)");
    }

    // ═════════════════════════════════════════════════════════════
    // DEX STAGING + DEPLOYMENT
    // ═════════════════════════════════════════════════════════════

    private boolean stageDexLocally() {
        try {
            File staged = new File(localStagingDexPath);
            if (staged.exists() && staged.length() > 0) {
                staged.setReadable(true, false);
                return true;
            }

            File parent = staged.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();

            logger.i("📋 Staging XposedHook.dex from assets to " + localStagingDexPath);

            try (InputStream is = getAssets().open("XposedHook.dex");
                 FileOutputStream fos = new FileOutputStream(staged)) {
                byte[] buffer = new byte[8192];
                int length;
                long total = 0;
                while ((length = is.read(buffer)) > 0) {
                    fos.write(buffer, 0, length);
                    total += length;
                }
                fos.flush();
                logger.i("✅ XposedHook.dex staged! Size: " + total + " bytes");
                staged.setReadable(true, false);
                return true;
            }
        } catch (Exception e) {
            logger.e("❌ stageDexLocally error: " + e.getMessage());
            return false;
        }
    }

    /**
     * Copy a native library from the APK's nativeLibraryDir into
     * external app storage, so the shell can read it later. Returns
     * the staged file, or null on failure.
     *
     * The platform extracts jniLibs/<abi>/*.so into
     * ApplicationInfo.nativeLibraryDir at install time. That path is
     * under /data/app/.../lib/<abi>/, readable by the app but not by
     * shell. Copying to getExternalFilesDir puts it somewhere shell
     * can reach with a plain read.
     */
    private File stageNativeLib(String libName) {
        try {
            File src = new File(getApplicationInfo().nativeLibraryDir, libName);
            if (!src.exists()) {
                logger.w("stageNativeLib: source missing: " + src.getAbsolutePath());
                return null;
            }

            File dst = new File(localStagingLibDir, libName);
            if (dst.exists() && dst.length() == src.length()) {
                dst.setReadable(true, false);
                return dst;
            }

            try (InputStream in = new FileInputStream(src);
                 FileOutputStream out = new FileOutputStream(dst)) {
                byte[] buf = new byte[8192];
                int n;
                long total = 0;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    total += n;
                }
                out.flush();
                logger.i("✅ Staged " + libName + " (" + total + " bytes)");
            }
            dst.setReadable(true, false);
            return dst;
        } catch (Throwable t) {
            logger.e("stageNativeLib(" + libName + ") failed: " + t.getMessage());
            return null;
        }
    }

    private boolean deployDexToShellDir() {
        if (!shizukuHelper.isAuthorized()) {
            logger.w("⚠️ Cannot deploy dex: Shizuku not authorized");
            return false;
        }
        if (!stageDexLocally()) return false;

        try {
            logger.i("📤 Deploying XposedHook.dex to shell dir via Shizuku...");

            ShellUtils.CommandResult mkdirResult =
                shizukuHelper.executeCommand("mkdir -p " + SHELL_FILES_DIR);
            if (!mkdirResult.isSuccess()) {
                logger.e("❌ Failed to create shell dir: " + mkdirResult.getStderrString());
                return false;
            }

            String copyCmd = "sh -c 'cat \"" + localStagingDexPath
                + "\" > \"" + SHELL_DEX_PATH + "\"'";
            ShellUtils.CommandResult copyResult = shizukuHelper.executeCommand(copyCmd);
            if (!copyResult.isSuccess()) {
                logger.e("❌ Failed to copy dex: " + copyResult.getStderrString());
                return false;
            }

            shizukuHelper.executeCommand("chmod 755 " + SHELL_DEX_PATH);
            shizukuHelper.executeCommand("chown shell:shell " + SHELL_DEX_PATH);

            ShellUtils.CommandResult verify =
                shizukuHelper.executeCommand("test -s " + SHELL_DEX_PATH + " && echo OK");
            if (!verify.isSuccess() || !verify.getStdoutString().contains("OK")) {
                logger.e("❌ Verification failed: dex not present");
                return false;
            }

            shizukuHelper.executeCommand("mkdir -p " + SHELL_CACHE_DIR);
            shizukuHelper.executeCommand("mkdir -p " + SHELL_MODULES_DIR);
            shizukuHelper.executeCommand("mkdir -p " + SHELL_HOOKED_DIR);
            shizukuHelper.executeCommand("chmod 755 " + SHELL_CACHE_DIR
                + " " + SHELL_MODULES_DIR
                + " " + SHELL_HOOKED_DIR);

            logger.i("✅ XposedHook.dex deployed to: " + SHELL_DEX_PATH);
            dexDeployed.set(true);
            return true;

        } catch (Exception e) {
            logger.e("❌ deployDexToShellDir error: " + e.getMessage());
            return false;
        }
    }

    // ═════════════════════════════════════════════════════════════
    // NATIVE LIBRARY DEPLOYMENT
    // ═════════════════════════════════════════════════════════════

    /**
     * Push libamiru.so and libshizuposed.so to the shell's own files
     * directory. XposedHook loads them from there inside the target
     * process via System.load().
     *
     * The manager process already has these libraries available via
     * System.loadLibrary() (the platform resolved them from the APK),
     * but the shell-side app_process is a different process with a
     * different UID. It cannot read the APK's nativeLibraryDir, so
     * the .so files must be copied to a shell-readable path first.
     */
    private boolean deployNativeLibsToShellDir() {
        if (!shizukuHelper.isAuthorized()) {
            logger.w("⚠️ Cannot deploy native libs: Shizuku not authorized");
            return false;
        }

        try {
            logger.i("📤 Deploying native libs to shell dir via Shizuku...");

            ShellUtils.CommandResult mkdir =
                shizukuHelper.executeCommand("mkdir -p " + SHELL_LIB_DIR);
            if (!mkdir.isSuccess()) {
                logger.e("❌ Failed to create shell lib dir: "
                    + mkdir.getStderrString());
                return false;
            }

            boolean amiruOk = pushOneLib(LIB_AMIRU, SHELL_AMIRU_PATH);
            boolean szpOk = pushOneLib(LIB_SHIZUPOSED, SHELL_SZP_SO_PATH);

            if (!amiruOk && !szpOk) {
                logger.e("❌ No native libs deployed");
                return false;
            }
            if (!amiruOk) {
                logger.w("⚠️ libamiru.so not deployed — Amiru will be unavailable");
            }
            if (!szpOk) {
                logger.w("⚠️ libshizuposed.so not deployed — NativeBackend will fall through");
            }

            logger.i("✅ Native libs deployed to: " + SHELL_LIB_DIR);
            libsDeployed.set(true);
            return true;

        } catch (Exception e) {
            logger.e("❌ deployNativeLibsToShellDir error: " + e.getMessage());
            return false;
        }
    }

    /**
     * Stage and push one native library. Returns true if the library
     * is present and readable at the destination path.
     */
    private boolean pushOneLib(String libName, String dstPath) {
        File staged = stageNativeLib(libName);
        if (staged == null) return false;

        String copy = "sh -c 'cat \"" + staged.getAbsolutePath()
            + "\" > \"" + dstPath + "\"'";
        ShellUtils.CommandResult r = shizukuHelper.executeCommand(copy);
        if (!r.isSuccess()) {
            logger.w("Failed to push " + libName + ": " + r.getStderrString());
            return false;
        }

        shizukuHelper.executeCommand("chmod 755 " + dstPath);

        ShellUtils.CommandResult verify =
            shizukuHelper.executeCommand("test -s " + dstPath + " && echo OK");
        if (!verify.isSuccess() || !verify.getStdoutString().contains("OK")) {
            logger.w("Verification failed for " + libName + " at " + dstPath);
            return false;
        }
        return true;
    }

    // ═════════════════════════════════════════════════════════════
    // PAYLOAD READINESS
    // ═════════════════════════════════════════════════════════════

    /**
     * Legacy name, kept for callers. Ensures the dex is deployed.
     * For full payload readiness (dex + libs) use ensurePayloadReady().
     */
    public boolean ensureDexReady() {
        if (dexDeployed.get()) return true;
        if (!shizukuHelper.isAuthorized()) {
            logger.w("⚠️ ensureDexReady called but Shizuku not authorized yet");
            return false;
        }
        return deployDexToShellDir();
    }

    /**
     * Ensure both the dex and the native libraries are deployed to
     * the shell side. Idempotent: the second call is a no-op if
     * everything is already in place.
     */
    public boolean ensurePayloadReady() {
        boolean dexOk = ensureDexReady();
        boolean libsOk = libsDeployed.get() || deployNativeLibsToShellDir();
        return dexOk && libsOk;
    }

    // ═════════════════════════════════════════════════════════════
    // MODULE PUSH
    // ═════════════════════════════════════════════════════════════

    private boolean pushModulesToShellDir(List<ModuleInfo> modules) {
        if (!shizukuHelper.isAuthorized()) return false;

        try {
            shizukuHelper.executeCommand("mkdir -p " + SHELL_MODULES_DIR);
            shizukuHelper.executeCommand("chmod 755 " + SHELL_CACHE_DIR + " " + SHELL_MODULES_DIR);

            int pushed = 0;
            for (ModuleInfo module : modules) {
                if (module == null || !module.enabled) continue;

                String json = gson.toJson(module);
                File localJson = new File(localStagingModulesDir, module.packageName + ".json");
                try (FileOutputStream fos = new FileOutputStream(localJson)) {
                    fos.write(json.getBytes());
                    fos.flush();
                }
                localJson.setReadable(true, false);

                String dstJson = SHELL_MODULES_DIR + "/" + module.packageName + ".json";
                String copyJson = "sh -c 'cat \"" + localJson.getAbsolutePath()
                    + "\" > \"" + dstJson + "\"'";
                ShellUtils.CommandResult r1 = shizukuHelper.executeCommand(copyJson);
                if (!r1.isSuccess()) {
                    logger.w("Failed to push JSON for " + module.packageName
                        + ": " + r1.getStderrString());
                    continue;
                }

                if (module.cachedDexPath != null) {
                    File localDex = new File(module.cachedDexPath);
                    if (localDex.exists()) {
                        String dstDex = SHELL_MODULES_DIR + "/" + module.packageName + ".dex";
                        String copyDex = "sh -c 'cat \"" + localDex.getAbsolutePath()
                            + "\" > \"" + dstDex + "\"'";
                        ShellUtils.CommandResult r2 = shizukuHelper.executeCommand(copyDex);
                        if (r2.isSuccess()) {
                            shizukuHelper.executeCommand("chmod 644 " + dstDex);

                            String patchedJson = json.replace(
                                localDex.getAbsolutePath(), dstDex);
                            File patchedLocal =
                                new File(localStagingModulesDir, module.packageName + ".json");
                            try (FileOutputStream fos = new FileOutputStream(patchedLocal)) {
                                fos.write(patchedJson.getBytes());
                                fos.flush();
                            }
                            String copyPatched = "sh -c 'cat \""
                                + patchedLocal.getAbsolutePath()
                                + "\" > \"" + dstJson + "\"'";
                            shizukuHelper.executeCommand(copyPatched);
                        }
                    }
                }

                pushed++;
            }

            logger.i("Pushed " + pushed + " module descriptors to shell dir");
            return pushed > 0;

        } catch (Exception e) {
            logger.e("pushModulesToShellDir error: " + e.getMessage());
            return false;
        }
    }

    // ═════════════════════════════════════════════════════════════
    // SERVICE STARTUP
    // ═════════════════════════════════════════════════════════════

    private void startServiceInternal() {
        if (!isRunning.compareAndSet(false, true)) return;

        workerHandler.post(() -> {
            try {
                logger.i("Starting ShizuPosed service...");

                List<ModuleInfo> modules = moduleLoader.loadModules();
                logger.i("Loaded " + modules.size() + " modules");

                if (shizukuHelper.isAuthorized()) {
                    logger.i("Shizuku authorized at startup — deploying payload...");
                    if (ensurePayloadReady()) {
                        pushModulesToShellDir(modules);
                    }
                } else {
                    logger.i("⏳ Shizuku not yet authorized — deploy deferred");
                }

                processMonitor.startMonitoring();

                logger.i("✅ ShizuPosed service started successfully");
                updateNotification("Service running");
            } catch (Exception e) {
                logger.e("❌ Failed to start service: " + e.getMessage());
                isRunning.set(false);
                updateNotification("Service error: " + e.getMessage());
            }
        });
    }

    private void prepareXposedHook() {
        if (!xposedHookStarted.compareAndSet(false, true)) return;

        if (!shizukuHelper.isAuthorized()) {
            logger.w("⚠️ Shizuku not authorized, cannot prepare XposedHook");
            xposedHookStarted.set(false);
            return;
        }
        if (!ensurePayloadReady()) {
            logger.e("❌ Cannot prepare XposedHook without deployed payload");
            xposedHookStarted.set(false);
            return;
        }
        if (!pushModulesToShellDir(moduleLoader.loadModules())) {
            logger.w("⚠️ No modules pushed to shell dir (nothing will hook)");
        }

        logger.i("✅ XposedHook ready (launcher will run on demand)");
        updateNotification("Ready");
    }

    public void onShizukuAuthorized() {
        if (!onAuthorizedDispatched.compareAndSet(false, true)) {
            if (logger != null) logger.d("onShizukuAuthorized() already dispatched — skipping");
            return;
        }
        logger.i("📢 onShizukuAuthorized() — deploying now");
        if (workerHandler == null) {
            onAuthorizedDispatched.set(false);
            return;
        }
        workerHandler.post(() -> {
            try {
                if (ensurePayloadReady()) {
                    prepareXposedHook();
                }
            } catch (Throwable t) {
                logger.e("onShizukuAuthorized worker failed: " + t.getMessage());
                onAuthorizedDispatched.set(false);
            }
        });
    }

    public void resetAuthorizedDispatch() {
        onAuthorizedDispatched.set(false);
    }

    // ═════════════════════════════════════════════════════════════
    // LAUNCH UNDER SHIZUPOSED
    // ═════════════════════════════════════════════════════════════

    public boolean launchAppUnderShizuPosed(String packageName) {
        if (!shizukuHelper.isAuthorized()) {
            logger.w("❌ Shizuku not authorized, cannot launch " + packageName);
            return false;
        }
        if (!ensurePayloadReady()) {
            logger.e("❌ Cannot launch " + packageName + ": payload not deployed");
            return false;
        }

        pushModulesToShellDir(moduleLoader.loadModules());

        String binary = shizukuHelper.getAppProcessBinary();
        if (binary == null) {
            logger.e("❌ No usable app_process binary on this ROM. "
                    + "Hooking is not possible on this device.");
            updateNotification("app_process unavailable");
            return false;
        }

        try {
            int targetUid = -1;
            try {
                ApplicationInfo ai =
                    getPackageManager().getApplicationInfo(packageName, 0);
                targetUid = ai.uid;
            } catch (Throwable ignored) {}

            // Pass the shell lib dir as a property so XposedHook can
            // find libamiru.so / libshizuposed.so without hardcoding.
            String cmd = String.format(
                "CLASSPATH=%s %s " +
                "-Xverify:none -Xallowinmemorycompilation " +
                "-Xcompiler-option --target-api=%d " +
                "-Djava.class.path=%s " +
                "-Dshizuposed.shell.libs=%s " +
                "/system/bin com.shizuposed.manager.core.XposedHook %s 0 %d &",
                SHELL_DEX_PATH,
                binary,
                Build.VERSION.SDK_INT,
                SHELL_DEX_PATH,
                SHELL_LIB_DIR,
                packageName,
                targetUid
            );

            logger.i("🚀 Launching " + packageName + " under ShizuPosed"
                + " (binary=" + binary + ", uid=" + targetUid + ")");
            logger.i("Executing: " + cmd);

            ShellUtils.CommandResult result = shizukuHelper.executeCommand(cmd);
            if (!result.isSuccess()) {
                logger.e("❌ " + binary + " launch failed: "
                    + result.getStderrString());
                return false;
            }

            logger.i("✅ " + binary + " spawned for " + packageName);
            return true;

        } catch (Exception e) {
            logger.e("❌ launchAppUnderShizuPosed error: " + e.getMessage());
            return false;
        }
    }

    public boolean injectProcess(String packageName, int pid, int uid,
                                 List<ModuleInfo> modules) {
        logger.i("📥 injectProcess(" + packageName + ", pid=" + pid
                + ", uid=" + uid + ")");
        if (!isRunning.get() || !shizukuHelper.isAuthorized()) return false;
        if (!ensurePayloadReady()) return false;
        logger.w("Cannot hook externally-launched process " + packageName
            + " — use launchAppUnderShizuPosed() to launch it under ShizuPosed");
        return false;
    }

    // ═════════════════════════════════════════════════════════════
    // ACCESSORS
    // ═════════════════════════════════════════════════════════════

    public boolean isRunning() { return isRunning.get(); }
    public boolean isXposedHookStarted() { return xposedHookStarted.get(); }
    public boolean isDexDeployed() { return dexDeployed.get(); }
    public boolean isLibsDeployed() { return libsDeployed.get(); }
    public static boolean isServiceRunning() { return isServiceRunning; }
}