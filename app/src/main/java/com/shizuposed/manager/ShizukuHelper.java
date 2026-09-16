package com.shizuposed.manager;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.widget.Toast;

import com.shizuposed.manager.utils.Logger;
import com.shizuposed.manager.utils.ShellUtils;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import rikka.shizuku.Shizuku;
import rikka.sui.Sui;

/**
 * Single source of truth for Shizuku / Shevery / Sui state.
 *
 * Design:
 *   - Listeners are registered synchronously in the constructor.
 *   - checkPermission() runs ONLY from onBinderReceived,
 *     refreshFromBinder(), and the request-result listener. Never
 *     eagerly during construction.
 *   - isAuthorized is monotonic during the settle window: a single
 *     transient PERMISSION_DENIED from checkSelfPermission() does
 *     not downgrade a known grant. This prevents the toolbar label
 *     from flip-flopping when the grant is written by Shizuku but
 *     the first re-read races it.
 *   - Provider detection is stable: Shevery is prioritized when
 *     installed, since Shevery ships a legacy stub under
 *     moe.shizuku.privileged.api and impersonates Shizuku. Reporting
 *     "Shizuku" while Shevery is active is misleading.
 *
 * Anything that needs Shizuku state should call into this class.
 * Do not call Shizuku.* directly from elsewhere in the app.
 */
public class ShizukuHelper {
    private static final String TAG = "ShizukuHelper";
    private static final int SHIZUKU_CODE = 0xCA07A;
    private static ShizukuHelper instance;

    /** Grace window after a successful grant during which a
     *  transient DENIED from checkSelfPermission() will not
     *  downgrade isAuthorized. */
    private static final long GRANT_SETTLE_MS = 5000L;

    private final Context context;
    private final Logger logger;

    private volatile boolean isAvailable = false;
    private volatile boolean isAuthorized = false;
    private volatile int shizukuVersion = 0;
    private volatile boolean isSui = false;
    private volatile boolean binderStatus = false;

    /** Timestamp of the last known grant, for the settle window. */
    private volatile long lastGrantTimestamp = 0L;

    private volatile boolean realShizukuInstalled = false;
    private volatile boolean sheveryInstalled = false;

    private static final String SHIZUKU_API_PACKAGE     = "moe.shizuku.privileged.api";
    private static final String SHIZUKU_MANAGER_PACKAGE = "moe.shizuku.manager";
    private static final String SHEVERY_PACKAGE         = "com.hamondev.shevery";

    private final AtomicBoolean permissionRequestInFlight = new AtomicBoolean(false);
    private final AtomicBoolean listenersRegistered = new AtomicBoolean(false);
    private final AtomicBoolean inAutoStart = new AtomicBoolean(false);
    private final AtomicBoolean grantToastShown = new AtomicBoolean(false);

    private static final String[] APP_PROCESS_CANDIDATES = {
        "/system/bin/app_process64",
        "/system/bin/app_process",
        "/system/bin/app_process32",
        "/apex/com.android.runtime/bin/app_process64",
        "/apex/com.android.runtime/bin/app_process",
    };

    private volatile String cachedAppProcessBinary = null;

    public interface PermissionListener {
        void onPermissionGranted();
        void onPermissionDenied();
    }

    private final List<PermissionListener> permissionListeners = new CopyOnWriteArrayList<>();

    // ═════════════════════════════════════════════════════════════
    // SHIZUKU CALLBACKS
    // ═════════════════════════════════════════════════════════════

    private final Shizuku.OnBinderReceivedListener binderListener =
        new Shizuku.OnBinderReceivedListener() {
        @Override
        public void onBinderReceived() {
            binderStatus = true;
            isAvailable = true;
            try {
                if (!Shizuku.isPreV11()) shizukuVersion = Shizuku.getVersion();
            } catch (Throwable ignored) {}
            logger.i("Shizuku binder received (v" + shizukuVersion
                    + ", provider=" + providerName() + ")");
            checkPermission("binderReceived");
        }
    };

    private final Shizuku.OnBinderDeadListener binderDeadListener =
        new Shizuku.OnBinderDeadListener() {
        @Override
        public void onBinderDead() {
            binderStatus = false;
            isAvailable = false;
            isAuthorized = false;
            lastGrantTimestamp = 0L;
            permissionRequestInFlight.set(false);
            grantToastShown.set(false);
            cachedAppProcessBinary = null;
            logger.w("Shizuku binder dead");
        }
    };

    private final Shizuku.OnRequestPermissionResultListener permissionResultListener =
        new Shizuku.OnRequestPermissionResultListener() {
        @Override
        public void onRequestPermissionResult(int requestCode, int grantResult) {
            if (requestCode != SHIZUKU_CODE) return;
            permissionRequestInFlight.set(false);

            if (grantResult == PackageManager.PERMISSION_GRANTED) {
                markAuthorized("onRequestPermissionResult");
                grantToastShown.set(true);
                logger.i("✅ Shizuku permission GRANTED via provider=" + providerName());
                notifyPermissionGranted();
                autoStartServiceIfPossible();
            } else {
                isAuthorized = false;
                lastGrantTimestamp = 0L;
                logger.w("❌ Shizuku permission DENIED via provider=" + providerName());
                notifyPermissionDenied();
            }
        }
    };

    // ═════════════════════════════════════════════════════════════
    // LIFECYCLE
    // ═════════════════════════════════════════════════════════════

    private ShizukuHelper(Context context) {
        this.context = context.getApplicationContext();
        this.logger = Logger.getInstance(this.context);
        registerListeners();
        initShizuku();
    }

    public static synchronized ShizukuHelper getInstance(Context context) {
        if (instance == null) instance = new ShizukuHelper(context);
        return instance;
    }

    private void registerListeners() {
        if (listenersRegistered.get()) return;
        synchronized (this) {
            if (listenersRegistered.get()) return;
            try {
                Shizuku.addBinderReceivedListenerSticky(binderListener);
                Shizuku.addBinderDeadListener(binderDeadListener);
                Shizuku.addRequestPermissionResultListener(permissionResultListener);
                listenersRegistered.set(true);
                logger.d("Shizuku listeners registered");
            } catch (Throwable t) {
                logger.e("Failed to register Shizuku listeners: " + t.getMessage());
            }
        }
    }

    private void initShizuku() {
        try {
            try {
                isSui = Sui.init(context.getPackageName());
                if (isSui) {
                    logger.i("✅ Sui detected — using Sui binder");
                    isAvailable = true;
                    return;
                }
            } catch (NoClassDefFoundError e) {
                logger.d("Sui not present");
                isSui = false;
            } catch (Throwable e) {
                logger.w("Sui init failed: " + e.getMessage());
                isSui = false;
            }

            // Detect Shevery FIRST. Shevery ships a compat stub at the
            // Shizuku package names, so if it's installed, "real Shizuku"
            // detection will report it as installed too. We treat
            // Shevery as the provider whenever it's present.
            sheveryInstalled = isPackageInstalled(SHEVERY_PACKAGE);
            realShizukuInstalled = !sheveryInstalled
                                && isPackageInstalled(SHIZUKU_MANAGER_PACKAGE)
                                && isPackageInstalled(SHIZUKU_API_PACKAGE);

            if (!realShizukuInstalled && !sheveryInstalled) {
                logger.w("Shizuku/Shevery not installed");
                isAvailable = false;
                return;
            }

            if (sheveryInstalled) {
                logger.i("✅ Shevery detected (com.hamondev.shevery) — provider=Shevery(stub)");
            } else if (realShizukuInstalled) {
                logger.i("✅ Real Shizuku detected — provider=Shizuku");
            }
            logBinderProvenance();

        } catch (NoClassDefFoundError e) {
            logger.e("Shizuku API not found: " + e.getMessage());
            isAvailable = false;
        } catch (Throwable t) {
            logger.e("Shizuku init failed: " + t.getMessage());
            isAvailable = false;
        }
    }

    private boolean isPackageInstalled(String pkg) {
        try {
            context.getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /**
     * Stable provider name. Shevery always wins when installed, because
     * it ships a compat stub under the Shizuku package names — reporting
     * "Shizuku" would be misleading.
     */
    private String providerName() {
        if (isSui) return "Sui";
        if (sheveryInstalled) return "Shevery(stub)";
        return "Shizuku";
    }

    private void logBinderProvenance() {
        try {
            int uid = android.os.Process.myUid();
            String[] packages = context.getPackageManager().getPackagesForUid(uid);
            logger.d("Binder provenance: uid=" + uid
                    + " packages=" + (packages == null ? "[]" : java.util.Arrays.toString(packages))
                    + " provider=" + providerName());
        } catch (Throwable ignored) {}
    }

    // ═════════════════════════════════════════════════════════════
    // PERMISSION CHECK
    // ═════════════════════════════════════════════════════════════

    private void markAuthorized(String source) {
        boolean was = isAuthorized;
        isAuthorized = true;
        lastGrantTimestamp = System.currentTimeMillis();
        if (!was) {
            logger.i("markAuthorized (source=" + source
                    + ", provider=" + providerName() + ")");
        }
    }

    private void checkPermission(String source) {
        if (!isAvailable) {
            logger.d("checkPermission(" + source + "): binder not available, skipping");
            return;
        }

        try {
            int result = Shizuku.checkSelfPermission();
            boolean granted = (result == PackageManager.PERMISSION_GRANTED);
            boolean was = isAuthorized;

            if (granted) {
                markAuthorized(source);
                if (!was) {
                    notifyPermissionGranted();
                    autoStartServiceIfPossible();
                }
                return;
            }

            if (was) {
                long sinceGrant = System.currentTimeMillis() - lastGrantTimestamp;
                if (sinceGrant < GRANT_SETTLE_MS) {
                    logger.w("checkPermission(" + source + "): transient DENIED "
                            + "within settle window (" + sinceGrant + "ms since grant) "
                            + "— keeping isAuthorized=true (provider=" + providerName() + ")");
                    return;
                }
                logger.w("checkPermission(" + source + "): DENIED after settle window "
                        + "(" + sinceGrant + "ms since grant) — downgrading");
            } else {
                logger.w("checkPermission(" + source + "): NOT GRANTED "
                        + "(provider=" + providerName() + ")");
            }

            isAuthorized = false;
            lastGrantTimestamp = 0L;

        } catch (Throwable e) {
            logger.e("checkPermission(" + source + ") failed: " + e.getMessage());
        }
    }

    public void refreshFromBinder() {
        try {
            if (isSui) {
                checkPermission("refresh(Sui)");
                return;
            }
            if (!Shizuku.pingBinder()) {
                isAvailable = false;
                return;
            }
            isAvailable = true;
            if (shizukuVersion == 0) {
                try {
                    if (!Shizuku.isPreV11()) shizukuVersion = Shizuku.getVersion();
                } catch (Throwable ignored) {}
            }
            checkPermission("refresh");
        } catch (Throwable t) {
            logger.e("refreshFromBinder failed: " + t.getMessage());
        }
    }

    // ═════════════════════════════════════════════════════════════
    // PERMISSION REQUEST
    // ═════════════════════════════════════════════════════════════

    public void requestPermission() {
        if (!isAvailable) {
            logger.w("Cannot request permission: Shizuku/Shevery not available");
            return;
        }
        if (isAuthorized) {
            logger.i("Shizuku already authorized");
            return;
        }
        if (!permissionRequestInFlight.compareAndSet(false, true)) {
            logger.d("Permission request already in flight — skipping");
            return;
        }

        try {
            logger.i("📢 Requesting Shizuku/Shevery permission...");
            Shizuku.requestPermission(SHIZUKU_CODE);

            if (grantToastShown.compareAndSet(false, true)) {
                final String target = providerName();
                new Handler(Looper.getMainLooper()).post(() ->
                    Toast.makeText(context,
                        "Please grant permission in " + target,
                        Toast.LENGTH_LONG).show());
            }
        } catch (Throwable e) {
            permissionRequestInFlight.set(false);
            logger.e("requestPermission failed: " + e.getMessage());
        }
    }

    public void forceRequestPermission() {
        permissionRequestInFlight.set(false);
        grantToastShown.set(false);
        refreshFromBinder();

        if (isAuthorized) {
            logger.i("Already authorized — no request needed");
            new Handler(Looper.getMainLooper()).post(() ->
                Toast.makeText(context, "Already authorized", Toast.LENGTH_SHORT).show());
            return;
        }
        if (!isAvailable) {
            logger.w("Cannot force-request: binder unavailable");
            new Handler(Looper.getMainLooper()).post(() ->
                Toast.makeText(context, "Shizuku is not running", Toast.LENGTH_SHORT).show());
            return;
        }

        logger.i("Force-requesting permission (provider=" + providerName() + ")");
        requestPermission();
    }

    // ═════════════════════════════════════════════════════════════
    // LISTENERS
    // ═════════════════════════════════════════════════════════════

    public void addPermissionListener(PermissionListener l) {
        if (l == null) return;
        if (!permissionListeners.contains(l)) permissionListeners.add(l);
        if (isAuthorized) {
            new Handler(Looper.getMainLooper()).post(l::onPermissionGranted);
        }
    }

    public void removePermissionListener(PermissionListener l) {
        permissionListeners.remove(l);
    }

    private void notifyPermissionGranted() {
        for (PermissionListener l : permissionListeners) {
            try { l.onPermissionGranted(); }
            catch (Throwable t) { logger.e("onPermissionGranted: " + t.getMessage()); }
        }
    }

    private void notifyPermissionDenied() {
        for (PermissionListener l : permissionListeners) {
            try { l.onPermissionDenied(); }
            catch (Throwable t) { logger.e("onPermissionDenied: " + t.getMessage()); }
        }
    }

    private void autoStartServiceIfPossible() {
        if (!inAutoStart.compareAndSet(false, true)) return;
        try {
            if (context instanceof ShizuPosedManagerApp) {
                ShizuPosedManagerApp app = (ShizuPosedManagerApp) context;
                new Handler(Looper.getMainLooper()).post(app::autoStartService);
            }
        } catch (Throwable t) {
            logger.e("autoStartServiceIfPossible: " + t.getMessage());
        } finally {
            inAutoStart.set(false);
        }
    }

    // ═════════════════════════════════════════════════════════════
    // QUERIES
    // ═════════════════════════════════════════════════════════════

    public boolean isAvailable() {
        if (isSui) return isAvailable;
        return isAvailable && binderStatus;
    }

    public boolean isAuthorized() { return isAuthorized; }
    public int getVersion() { return shizukuVersion; }
    public boolean isSui() { return isSui; }

    public boolean isAuthorizedFresh() {
        try {
            if (isSui) return isAuthorized;
            if (!Shizuku.pingBinder()) return false;
            int result = Shizuku.checkSelfPermission();
            return result == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            logger.e("isAuthorizedFresh failed: " + t.getMessage());
            return false;
        }
    }

    public boolean isSheveryInstalledPublic() { return sheveryInstalled; }

    public boolean refreshSheveryPresence() {
        sheveryInstalled = isPackageInstalled(SHEVERY_PACKAGE);
        realShizukuInstalled = !sheveryInstalled
                            && isPackageInstalled(SHIZUKU_MANAGER_PACKAGE)
                            && isPackageInstalled(SHIZUKU_API_PACKAGE);
        return sheveryInstalled;
    }

    public boolean isSheveryOnly() {
        return sheveryInstalled && !isSui;
    }

    public ShizukuStatus checkShizukuActive() {
        if (isSui) return ShizukuStatus.ACTIVE;
        boolean apiInstalled = isPackageInstalled(SHIZUKU_API_PACKAGE);
        boolean managerInstalled = isPackageInstalled(SHIZUKU_MANAGER_PACKAGE);
        boolean sheveryPresent = isPackageInstalled(SHEVERY_PACKAGE);
        sheveryInstalled = sheveryPresent;
        realShizukuInstalled = !sheveryPresent && managerInstalled && apiInstalled;
        if (!apiInstalled && !managerInstalled && !sheveryPresent) {
            return ShizukuStatus.NOT_INSTALLED;
        }
        try {
            if (Shizuku.pingBinder() && !Shizuku.isPreV11()) {
                return ShizukuStatus.ACTIVE;
            }
        } catch (Throwable ignored) {}
        return ShizukuStatus.NOT_ACTIVE;
    }

    /**
     * The single source of truth for the toolbar label.
     */
    public String getStatusString() {
        if (isSui) {
            return isAuthorized
                ? "🔑 Privileged (Sui)"
                : "⚠️ Sui Active (Not Authorized)";
        }

        final String provider = providerName();

        if (!isAvailable()) {
            return "❌ " + provider + " Not Active";
        }
        if (!isAuthorized) {
            return "⚠️ " + provider + " Available (Not Authorized)";
        }
        return "🔑 Privileged (" + provider + " v" + shizukuVersion + ")";
    }

    // ═════════════════════════════════════════════════════════════
    // COMMAND EXECUTION
    // ═════════════════════════════════════════════════════════════

    public String getAppProcessBinary() {
        String cached = cachedAppProcessBinary;
        if (cached != null) return cached;
        if (!isAvailable() || !isAuthorized) {
            logger.d("Cannot probe app_process: not ready");
            return null;
        }
        for (String candidate : APP_PROCESS_CANDIDATES) {
            if (probeAppProcess(candidate)) {
                cachedAppProcessBinary = candidate;
                logger.i("app_process binary selected: " + candidate);
                return candidate;
            }
        }
        logger.e("No usable app_process binary on this ROM");
        return null;
    }

    public void invalidateAppProcessCache() { cachedAppProcessBinary = null; }

    private boolean probeAppProcess(String name) {
        try {
            String command = name.startsWith("/") ? "[ -x " + name + " ]" : "command -v " + name;
            String cmd = command + " >/dev/null 2>&1 && printf '__APP_PROCESS_FOUND__'";
            ShellUtils.CommandResult r = executeCommand(cmd);
            if (r == null) return false;
            boolean found = r.isSuccess()
                         && r.getStdoutString().contains("__APP_PROCESS_FOUND__");
            return found;
        } catch (Throwable t) {
            return false;
        }
    }

    public ShellUtils.CommandResult executeCommand(String command) {
        ShellUtils.CommandResult result = new ShellUtils.CommandResult();
        if (!isAvailable() || !isAuthorized) {
            result.stderr.add("Shizuku not available or not authorized");
            result.exitCode = -1;
            return result;
        }
        Process process = null;
        try {
            process = spawnShellViaAidl(new String[]{"sh", "-c", command}, null, null);
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            BufferedReader errorReader = new BufferedReader(new InputStreamReader(process.getErrorStream()));
            String line;
            while ((line = reader.readLine()) != null) result.stdout.add(line);
            while ((line = errorReader.readLine()) != null) result.stderr.add(line);
            result.exitCode = process.waitFor();
            reader.close();
            errorReader.close();
        } catch (Exception e) {
            logger.e("Command execution failed: " + e.getMessage());
            result.stderr.add(e.getMessage());
            result.exitCode = -1;
        } finally {
            if (process != null) try { process.destroy(); } catch (Throwable ignored) {}
        }
        return result;
    }

    private Process spawnShellViaAidl(String[] cmd, String[] env, String dir) throws Exception {
        android.os.IBinder binder = Shizuku.getBinder();
        if (binder == null) throw new IllegalStateException("Shizuku binder is null");
        moe.shizuku.server.IShizukuService service =
            moe.shizuku.server.IShizukuService.Stub.asInterface(binder);
        moe.shizuku.server.IRemoteProcess remote = service.newProcess(cmd, env, dir);
        if (remote == null) throw new IllegalStateException("Shizuku returned null process");
        return new AidlProcessAdapter(remote);
    }

    private static final class AidlProcessAdapter extends Process {
        private final moe.shizuku.server.IRemoteProcess remote;
        private InputStream in; private InputStream err; private OutputStream out;
        private ParcelFileDescriptor inPfd, errPfd, outPfd;
        private Integer cachedExit;
        AidlProcessAdapter(moe.shizuku.server.IRemoteProcess remote) { this.remote = remote; }
        @Override public synchronized OutputStream getOutputStream() {
            if (out == null) try {
                outPfd = remote.getOutputStream();
                out = new ParcelFileDescriptor.AutoCloseOutputStream(outPfd);
            } catch (Exception e) { throw new RuntimeException(e); }
            return out;
        }
        @Override public synchronized InputStream getInputStream() {
            if (in == null) try {
                inPfd = remote.getInputStream();
                in = new ParcelFileDescriptor.AutoCloseInputStream(inPfd);
            } catch (Exception e) { throw new RuntimeException(e); }
            return in;
        }
        @Override public synchronized InputStream getErrorStream() {
            if (err == null) try {
                errPfd = remote.getErrorStream();
                err = new ParcelFileDescriptor.AutoCloseInputStream(errPfd);
            } catch (Exception e) { throw new RuntimeException(e); }
            return err;
        }
        @Override public int waitFor() throws InterruptedException {
            try { int c = remote.waitFor(); cachedExit = c; return c; }
            catch (android.os.RemoteException e) { throw new RuntimeException(e); }
        }
        @Override public synchronized int exitValue() {
            if (cachedExit != null) return cachedExit;
            try { int c = remote.exitValue(); cachedExit = c; return c; }
            catch (android.os.RemoteException e) { throw new RuntimeException(e); }
        }
        @Override public void destroy() {
            try { remote.destroy(); } catch (android.os.RemoteException ignored) {}
        }
    }

    public boolean launchXposedHook(String packageName, int pid, int uid, String dexPath) {
        if (!isAuthorized()) {
            logger.w("Cannot launch: Shizuku not authorized");
            return false;
        }
        String binary = getAppProcessBinary();
        if (binary == null) {
            logger.e("Cannot launch: no usable app_process binary");
            return false;
        }
        try {
            String cmd = String.format(
                "%s -Xverify:none -Xallowinmemorycompilation " +
                "-Xcompiler-option --target-api=%d " +
                "-cp %s /system/bin XposedHook %s %d %d &",
                binary, Build.VERSION.SDK_INT, dexPath, packageName, pid, uid);
            return executeCommand(cmd).isSuccess();
        } catch (Exception e) {
            logger.e("launchXposedHook error: " + e.getMessage());
            return false;
        }
    }

    public void cleanup() {
        try {
            Shizuku.removeBinderReceivedListener(binderListener);
            Shizuku.removeBinderDeadListener(binderDeadListener);
            Shizuku.removeRequestPermissionResultListener(permissionResultListener);
        } catch (Exception ignored) {}
        listenersRegistered.set(false);
        permissionListeners.clear();
        cachedAppProcessBinary = null;
    }

    public enum ShizukuStatus { ACTIVE, NOT_ACTIVE, NOT_INSTALLED }
}