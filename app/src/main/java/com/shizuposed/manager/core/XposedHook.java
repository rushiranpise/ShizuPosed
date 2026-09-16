package com.shizuposed.manager.core;

import android.app.Application;
import android.content.pm.ApplicationInfo;
import android.content.res.Resources;
import android.os.Build;
import android.os.Process;

import com.shizuposed.manager.core.compat.AndroidCompat;
import com.shizuposed.manager.core.compat.HiddenApiBypass;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import dalvik.system.DexClassLoader;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XResources;
import de.robv.android.xposed.callbacks.XC_InitPackageResources;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

@SuppressWarnings({"unchecked", "rawtypes", "deprecation"})
public class XposedHook {

    // ─── paths ────────────────────────────────────────────────────────

    private static final String SHELL_FILES_DIR = "/data/user/0/com.android.shell/files";
    private static final String BASE_DIR        = SHELL_FILES_DIR + "/.syscall_cache";
    private static final String MODULES_DIR     = BASE_DIR + "/modules";
    private static final String HOOKED_DIR      = BASE_DIR + "/hooked";
    private static final String LIBS_DIR        = SHELL_FILES_DIR + "/libs";
    private static final String STATUS_FILE     = BASE_DIR + "/status";
    private static final String LOG_FILE        = BASE_DIR + "/xposed.log";

    // ─── native library names ─────────────────────────────────────────

    private static final String LIB_AMIRU       = "libamiru.so";
    private static final String LIB_SHIZUPOSED  = "libshizuposed.so";

    // ─── state ────────────────────────────────────────────────────────

    private static int myPid = 0;
    private static int myUid = 0;
    private static String targetPackage = null;
    private static int targetUid = -1;

    private static final Map<String, Object> moduleInstances = new ConcurrentHashMap<>();

    private static final List<String> loadedModuleNames = new ArrayList<>();

    /** Track which native libs loaded, for the marker/diagnostics. */
    private static volatile boolean amiruLoaded = false;
    private static volatile boolean shizuposedLoaded = false;

    // ─── data holder ──────────────────────────────────────────────────

    private static final class ModuleInfo {
        String packageName;
        String name;
        String xposedInit;
        String cachedDexPath;
        boolean enabled;
        Set<String> hookedApps = new HashSet<>();
        boolean hookAllApps;
        boolean hookSystemApps;
    }

    // ═════════════════════════════════════════════════════════════════
    // ENTRY POINT
    // ═════════════════════════════════════════════════════════════════

    public static void main(String[] args) {
        try {
            myPid = Process.myPid();
            myUid = Process.myUid();

            synchronized (loadedModuleNames) {
                loadedModuleNames.clear();
            }
            moduleInstances.clear();

            initDirectories();

            logBox("XposedHook", "pid=" + myPid + " uid=" + myUid
                + " " + AndroidCompat.describe()
                + " args=" + java.util.Arrays.toString(args));

            if (args.length >= 1) {
                targetPackage = args[0];
            }

            int expectedPid = -1;
            if (args.length >= 2) {
                try { expectedPid = Integer.parseInt(args[1]); }
                catch (NumberFormatException ignored) {}
            }

            if (args.length >= 3) {
                try { targetUid = Integer.parseInt(args[2]); }
                catch (NumberFormatException ignored) {}
            }

            if (targetPackage == null || targetPackage.isEmpty()) {
                log("No target package provided — nothing to do.");
                writeStatus("IDLE", "no target package");
                return;
            }

            if (expectedPid > 0 && expectedPid != myPid) {
                log("Expected pid " + expectedPid + " but running as pid " + myPid);
                log("Refusing to run — cannot install hooks from an external process.");
                writeStatus("SKIPPED_NOT_IN_TARGET",
                    "myPid=" + myPid + " expectedPid=" + expectedPid);
                return;
            }

            // ── Load native engines ───────────────────────────────────
            // Must happen before HookEngine.ensureBackendInstalled(),
            // because the backend chain probes isAvailable() at
            // registration time and both AmiruBackend and NativeBackend
            // depend on their libraries being loaded.
            loadNativeLibs();

            boolean useBootstrap = (targetUid > 0);

            HookEngine.ensureBackendInstalled();

            ResourceHooking.getInstanceSafe().init();

            if (useBootstrap) {
                log("Mode: BOOTSTRAP (target uid " + targetUid + ")");
                boolean bootstrapped = runBootstrapMode(targetPackage);
                if (!bootstrapped) {
                    log("Bootstrap failed — falling back to post-Application mode");
                    writeStatus("BOOTSTRAP_FAILED", targetPackage);
                    runPostApplicationMode(targetPackage);
                }
            } else {
                log("Mode: POST-APPLICATION (no target uid provided)");
                runPostApplicationMode(targetPackage);
            }

        } catch (Throwable t) {
            log("Fatal: " + t);
            t.printStackTrace();
            try { writeStatus("ERROR", String.valueOf(t.getMessage())); }
            catch (Throwable ignored) {}
        }
    }

    // ═════════════════════════════════════════════════════════════════
    // NATIVE LIBRARY LOADING
    // ═════════════════════════════════════════════════════════════════

    /**
     * Load the two native engines from the shell-side lib directory.
     *
     *   • libshizuposed.so — the original shared-dispatcher engine.
     *     Loaded first so its JNI_OnLoad runs before anything that
     *     might call into it via NativeBridge.
     *
     *   • libamiru.so — the per-method stub engine. Optional. If it
     *     fails to load, AmiruBackend reports unavailable and the
     *     dispatcher falls through to the other backends.
     *
     * Both libraries live in SHELL_FILES_DIR/libs, which was
     * populated by ShizuPosedService.deployNativeLibsToShellDir()
     * before this process was launched. The directory is readable
     * by the shell UID because that is where the service pushed it.
     *
     * The path can be overridden via the -Dshizuposed.shell.libs
     * system property, which ShizuPosedService sets when it builds
     * the app_process command line. If the property is missing, we
     * fall back to LIBS_DIR.
     */
    private static void loadNativeLibs() {
        String libDir = System.getProperty("shizuposed.shell.libs", LIBS_DIR);
        log("Loading native libs from: " + libDir);

        // 1. libshizuposed.so — required for the existing native engine.
        String szpPath = libDir + "/" + LIB_SHIZUPOSED;
        try {
            System.load(szpPath);
            shizuposedLoaded = true;
            log("✅ Loaded " + LIB_SHIZUPOSED + " from " + szpPath);
        } catch (Throwable t) {
            shizuposedLoaded = false;
            log("⚠️ " + LIB_SHIZUPOSED + " not loaded: " + t.getMessage());
            // Not fatal — Pine is still the primary engine. But
            // NativeBackend will report unavailable.
        }

        // 2. libamiru.so — optional, provides the per-method stub engine.
        String amiruPath = libDir + "/" + LIB_AMIRU;
        try {
            System.load(amiruPath);
            amiruLoaded = true;
            log("✅ Loaded " + LIB_AMIRU + " from " + amiruPath);
        } catch (Throwable t) {
            amiruLoaded = false;
            log("⚠️ " + LIB_AMIRU + " not loaded (optional): " + t.getMessage());
            // Not fatal — AmiruBackend will decline every hook and
            // the dispatcher will fall through.
        }

        log("Native engines: shizuposed=" + shizuposedLoaded
            + " amiru=" + amiruLoaded);
    }

    // ═════════════════════════════════════════════════════════════════
    // MODE 1 — BOOTSTRAP
    // ═════════════════════════════════════════════════════════════════

    private static boolean runBootstrapMode(String pkg) {
        try {
            if (targetUid > 0 && targetUid != myUid) {
                boolean switched = switchToUser(targetUid);
                if (switched) {
                    myUid = Process.myUid();
                    log("Switched to target uid " + myUid);
                } else {
                    log("Continuing as uid " + myUid
                        + " (target uid " + targetUid + ")");
                }
            }

            ApplicationInfo appInfo = resolveApplicationInfo(pkg);
            if (appInfo == null) {
                log("Could not resolve ApplicationInfo for " + pkg);
                return false;
            }
            log("Resolved ApplicationInfo for " + pkg
                + " (uid=" + appInfo.uid
                + ", sourceDir=" + appInfo.sourceDir + ")");

            Class<?> at = Class.forName("android.app.ActivityThread");
            Object activityThread = obtainActivityThread(at);
            if (activityThread == null) {
                log("Could not obtain ActivityThread");
                return false;
            }
            log("ActivityThread obtained: " + activityThread.getClass().getName());

            ClassLoader appLoader = resolveClassLoader(activityThread, appInfo);
            if (appLoader == null) {
                log("Could not resolve classloader for " + pkg);
                return false;
            }
            log("Using classloader: " + appLoader.getClass().getName());

            int hooksFrom = installModuleHooks(pkg, appLoader, appInfo);

            try {
                Resources targetResources = resolveTargetResources(activityThread, appInfo);
                if (targetResources != null) {
                    callInitPackageResources(pkg, targetResources);
                } else {
                    log("Could not resolve target Resources — resource hooks skipped");
                }
            } catch (Throwable t) {
                log("Resource hooking setup failed: " + t.getMessage());
            }

            try {
                com.shizuposed.manager.core.backends.InstrumentationBackend.install(pkg);
            } catch (Throwable t) {
                log("Instrumentation install failed: " + t.getMessage());
            }

            tryInstallContentProviders(activityThread, appInfo, appLoader);

            boolean bound = bindApplication(activityThread, appInfo, pkg);
            if (!bound) {
                log("handleBindApplication failed");
                return false;
            }

            log("Application bound; hooks live for everything after this point");
            writeStatus("HOOKED",
                pkg + "|pid=" + myPid + "|modules=" + hooksFrom + "|bootstrap=true");

            if (hooksFrom > 0) {
                writeHookedMarker(pkg, hooksFrom, "bootstrap");
            }

            log("Entering Looper.loop()");
            Class<?> looperClass = Class.forName("android.os.Looper");
            try {
                Method prepare = looperClass.getDeclaredMethod("prepareMainLooper");
                HiddenApiBypass.forceAccessible(prepare);
                prepare.invoke(null);
            } catch (Throwable ignored) {}

            Method loop = looperClass.getMethod("loop");
            loop.invoke(null);

            log("Looper.loop() returned — process is exiting");
            return true;

        } catch (Throwable t) {
            log("runBootstrapMode failed: " + t);
            t.printStackTrace();
            return false;
        }
    }

    private static Object obtainActivityThread(Class<?> at) {
        try {
            Method systemMain = findMethodAny(at, "systemMain", new Class<?>[]{});
            if (systemMain != null) {
                Object t = systemMain.invoke(null);
                if (t != null) {
                    log("ActivityThread via systemMain()");
                    return t;
                }
            }
        } catch (Throwable t) {
            log("systemMain threw: " + t.getMessage());
        }

        try {
            Method current = findMethodAny(at, "currentActivityThread", new Class<?>[]{});
            if (current != null) {
                Object t = current.invoke(null);
                if (t != null) {
                    log("ActivityThread via currentActivityThread()");
                    return t;
                }
            }
        } catch (Throwable t) {
            log("currentActivityThread threw: " + t.getMessage());
        }

        try {
            Constructor<?> ctor = at.getDeclaredConstructor();
            HiddenApiBypass.forceAccessible(ctor);
            Object t = ctor.newInstance();
            log("ActivityThread via constructor");
            return t;
        } catch (Throwable t) {
            log("ActivityThread constructor threw: " + t.getMessage());
        }

        return null;
    }

    private static ClassLoader resolveClassLoader(Object activityThread,
                                                   ApplicationInfo appInfo) {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Class<?> compatInfoClass = Class.forName("android.content.res.CompatibilityInfo");

            Method getPackageInfoNoCheck = findMethodAny(at, "getPackageInfoNoCheck",
                new Class<?>[]{ApplicationInfo.class, compatInfoClass},
                new Class<?>[]{ApplicationInfo.class}
            );
            if (getPackageInfoNoCheck == null) {
                log("getPackageInfoNoCheck not found in any known signature");
                return null;
            }

            Object loadedApk;
            if (getPackageInfoNoCheck.getParameterCount() == 2) {
                loadedApk = getPackageInfoNoCheck.invoke(activityThread, appInfo, null);
            } else {
                loadedApk = getPackageInfoNoCheck.invoke(activityThread, appInfo);
            }
            if (loadedApk == null) {
                log("LoadedApk is null");
                return null;
            }

            Method getClassLoader = loadedApk.getClass().getMethod("getClassLoader");
            HiddenApiBypass.forceAccessible(getClassLoader);
            Object cl = getClassLoader.invoke(loadedApk);
            if (cl instanceof ClassLoader) return (ClassLoader) cl;
        } catch (Throwable t) {
            log("resolveClassLoader (via LoadedApk) failed: " + t.getMessage());
        }

        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Method getSystemContext = at.getDeclaredMethod("getSystemContext");
            HiddenApiBypass.forceAccessible(getSystemContext);
            Object sysCtx = getSystemContext.invoke(activityThread);
            if (sysCtx != null) {
                Method getClassLoader = sysCtx.getClass().getMethod("getClassLoader");
                Object cl = getClassLoader.invoke(sysCtx);
                if (cl instanceof ClassLoader) {
                    log("Using fallback classloader from system context");
                    return (ClassLoader) cl;
                }
            }
        } catch (Throwable t) {
            log("resolveClassLoader (fallback) failed: " + t.getMessage());
        }

        return XposedHook.class.getClassLoader();
    }

    private static Resources resolveTargetResources(Object activityThread,
                                                     ApplicationInfo appInfo) {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Class<?> compatInfoClass = Class.forName(
                "android.content.res.CompatibilityInfo");

            Method getPackageInfoNoCheck = findMethodAny(at, "getPackageInfoNoCheck",
                new Class<?>[]{ApplicationInfo.class, compatInfoClass},
                new Class<?>[]{ApplicationInfo.class}
            );
            if (getPackageInfoNoCheck == null) return null;

            Object loadedApk;
            if (getPackageInfoNoCheck.getParameterCount() == 2) {
                loadedApk = getPackageInfoNoCheck.invoke(activityThread, appInfo, null);
            } else {
                loadedApk = getPackageInfoNoCheck.invoke(activityThread, appInfo);
            }
            if (loadedApk == null) return null;

            Method getResources = null;
            try {
                getResources = loadedApk.getClass().getMethod("getResources");
            } catch (NoSuchMethodException ignored) {}

            if (getResources == null) {
                try {
                    getResources = loadedApk.getClass().getDeclaredMethod(
                        "getResources", compatInfoClass);
                    HiddenApiBypass.forceAccessible(getResources);
                } catch (NoSuchMethodException ignored) {}
            }

            if (getResources == null) {
                log("LoadedApk.getResources not found in any known signature");
                return null;
            }

            Object res;
            if (getResources.getParameterCount() == 0) {
                HiddenApiBypass.forceAccessible(getResources);
                res = getResources.invoke(loadedApk);
            } else {
                res = getResources.invoke(loadedApk, (Object) null);
            }

            if (res instanceof Resources) {
                log("Resolved target Resources via LoadedApk");
                return (Resources) res;
            }
        } catch (Throwable t) {
            log("resolveTargetResources failed: " + t.getMessage());
        }
        return null;
    }

    private static boolean bindApplication(Object activityThread,
                                            ApplicationInfo appInfo,
                                            String pkg) {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Class<?> dataClass = Class.forName("android.app.ActivityThread$AppBindData");

            Method bindApplication = findMethodAny(at, "handleBindApplication",
                new Class<?>[]{dataClass},
                new Class<?>[]{android.content.Context.class, dataClass}
            );
            if (bindApplication == null) {
                bindApplication = findMethodAny(at, "bindApplication",
                    new Class<?>[]{dataClass},
                    new Class<?>[]{android.content.Context.class, dataClass}
                );
            }
            if (bindApplication == null) {
                log("handleBindApplication / bindApplication not found");
                return false;
            }

            Object data = buildAppBindData(dataClass, appInfo, pkg);

            if (bindApplication.getParameterCount() == 2) {
                Method getSystemContext = at.getDeclaredMethod("getSystemContext");
                HiddenApiBypass.forceAccessible(getSystemContext);
                Object sysCtx = getSystemContext.invoke(activityThread);
                bindApplication.invoke(activityThread, sysCtx, data);
            } else {
                bindApplication.invoke(activityThread, data);
            }
            return true;
        } catch (Throwable t) {
            log("bindApplication failed: " + t);
            t.printStackTrace();
            return false;
        }
    }

    private static Object buildAppBindData(Class<?> dataClass,
                                            ApplicationInfo appInfo,
                                            String pkg) throws Throwable {
        Object data = dataClass.getDeclaredConstructor().newInstance();

        setFieldAny(data, appInfo, "appInfo");
        setFieldAny(data, pkg, "processName");
        setFieldAny(data, null, "providers");
        setFieldAny(data, 0, "debugMode", "debug");

        try {
            Class<?> compatInfoClass = Class.forName(
                "android.content.res.CompatibilityInfo");
            Field defaultCompat = compatInfoClass.getField("DEFAULT_COMPATIBILITY_INFO");
            Object compat = defaultCompat.get(null);
            setFieldAny(data, compat, "compatInfo", "compatibilityInfo");
        } catch (Throwable ignored) {}

        try {
            appInfo.processName = pkg;
        } catch (Throwable ignored) {}

        return data;
    }

    private static void tryInstallContentProviders(Object activityThread,
                                                    ApplicationInfo appInfo,
                                                    ClassLoader appLoader) {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");

            Method getProviderList = findMethodAny(at, "getProviderList",
                new Class<?>[]{ApplicationInfo.class}
            );
            if (getProviderList == null) {
                log("getProviderList not found");
                return;
            }
            Object providers = getProviderList.invoke(activityThread, appInfo);
            if (!(providers instanceof List)) {
                log("getProviderList returned no providers");
                return;
            }
            List<?> providerList = (List<?>) providers;
            if (providerList.isEmpty()) {
                log("Target has no ContentProviders");
                return;
            }

            Method getSystemContext = at.getDeclaredMethod("getSystemContext");
            HiddenApiBypass.forceAccessible(getSystemContext);
            android.content.Context sysCtx =
                (android.content.Context) getSystemContext.invoke(activityThread);
            if (sysCtx == null) {
                log("No system context for ContentProvider install");
                return;
            }

            Method installProviders = findMethodAny(at, "installContentProviders",
                new Class<?>[]{android.content.Context.class, List.class},
                new Class<?>[]{List.class}
            );
            if (installProviders == null) {
                log("installContentProviders not found");
                return;
            }

            if (installProviders.getParameterCount() == 2) {
                installProviders.invoke(activityThread, sysCtx, providerList);
            } else {
                installProviders.invoke(activityThread, providerList);
            }
            log("Installed " + providerList.size() + " ContentProvider(s)");
        } catch (Throwable t) {
            log("installContentProviders skipped: " + t.getMessage());
        }
    }

    // ═════════════════════════════════════════════════════════════════
    // BOOTSTRAP HELPERS
    // ═════════════════════════════════════════════════════════════════

    private static boolean switchToUser(int uid) {
        if (uid <= 0) return false;
        try {
            Class<?> osProcess = Class.forName("android.os.Process");
            Method setUid = osProcess.getDeclaredMethod("setUid", int.class);
            Method setGid = osProcess.getDeclaredMethod("setGid", int.class);
            HiddenApiBypass.forceAccessible(setUid);
            HiddenApiBypass.forceAccessible(setGid);

            setGid.invoke(null, uid);
            setUid.invoke(null, uid);

            Method myUid = osProcess.getMethod("myUid");
            int current = (Integer) myUid.invoke(null);
            return current == uid;
        } catch (Throwable t) {
            log("switchToUser(" + uid + ") failed: " + t.getMessage());
            return false;
        }
    }

    private static ApplicationInfo resolveApplicationInfo(String pkg) {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Method getPM = at.getMethod("getPackageManager");
            Object pm = getPM.invoke(null);

            Method getApplicationInfo = pm.getClass().getMethod(
                "getApplicationInfo", String.class, int.class);
            Object info = getApplicationInfo.invoke(pm, pkg, 0);
            return (ApplicationInfo) info;
        } catch (Throwable t) {
            log("resolveApplicationInfo failed: " + t.getMessage());
            return null;
        }
    }

    // ═════════════════════════════════════════════════════════════════
    // LOADPACKAGEPARAM BUILDER
    // ═════════════════════════════════════════════════════════════════

    private static XC_LoadPackage.LoadPackageParam buildLoadPackageParam(
            String pkg, ClassLoader appLoader, ApplicationInfo appInfo) {

        XC_LoadPackage.LoadPackageParam param = new XC_LoadPackage.LoadPackageParam();
        param.packageName = pkg;
        param.processName = pkg;
        param.classLoader = appLoader;
        param.isFirstApplication = true;

        if (appInfo == null) {
            appInfo = resolveApplicationInfo(pkg);
        }
        param.appInfo = appInfo;

        try {
            param.args = new Object[0];
        } catch (Throwable ignored) {}

        log("LoadPackageParam: pkg=" + param.packageName
            + " process=" + param.processName
            + " loader=" + (param.classLoader != null
                ? param.classLoader.getClass().getSimpleName() : "null")
            + " appInfo=" + (param.appInfo != null ? "set" : "null")
            + " isFirst=" + param.isFirstApplication);

        return param;
    }

    private static int installModuleHooks(String pkg,
                                          ClassLoader appLoader,
                                          ApplicationInfo appInfo) {
        try {
            List<ModuleInfo> modules = loadApplicableModules(pkg);
            if (modules.isEmpty()) {
                log("No applicable modules for " + pkg);
                return 0;
            }

            XC_LoadPackage.LoadPackageParam param =
                buildLoadPackageParam(pkg, appLoader, appInfo);

            int loaded = 0;
            for (ModuleInfo m : modules) {
                if (loadModule(m, null, appLoader, param)) loaded++;
            }

            log("Installed hooks from " + loaded + " module(s) before Application start");
            return loaded;
        } catch (Throwable t) {
            log("installModuleHooks failed: " + t);
            t.printStackTrace();
            return 0;
        }
    }

    // ═════════════════════════════════════════════════════════════════
    // IXposedHookInitPackageResources
    // ═════════════════════════════════════════════════════════════════

    private static void callInitPackageResources(String pkg, Resources resources) {
        if (resources == null) {
            log("callInitPackageResources: resources is null, skipping");
            return;
        }

        ResourceHooking resourceHooking = ResourceHooking.getInstanceSafe();
        XResources xResources = resourceHooking.getOrCreateXResources(pkg, resources);

        XC_InitPackageResources.InitPackageResourcesParam param =
            new XC_InitPackageResources.InitPackageResourcesParam();
        param.packageName = pkg;
        param.res = xResources;

        int handled = 0;

        for (Map.Entry<String, Object> entry : moduleInstances.entrySet()) {
            Object instance = entry.getValue();
            if (instance == null) continue;

            try {
                Class<?> moduleClass = instance.getClass();
                Method target = null;
                for (Method m : moduleClass.getDeclaredMethods()) {
                    if (!m.getName().equals("handleInitPackageResources")) continue;
                    if (m.getParameterCount() != 1) continue;
                    target = m;
                    break;
                }
                if (target == null) continue;

                HiddenApiBypass.forceAccessible(target);
                log("Calling handleInitPackageResources on "
                    + moduleClass.getName() + " for pkg=" + pkg);
                target.invoke(instance, param);
                handled++;

            } catch (Throwable t) {
                log("handleInitPackageResources failed for " + entry.getKey()
                    + ": " + t.getMessage());
            }
        }

        log("handleInitPackageResources handled by " + handled + " module(s)");
        log("XResources for " + pkg + " has " + xResources.replacementCount()
            + " replacement(s)");

        if (xResources.replacementCount() > 0) {
            resourceHooking.installResourcesHooks(pkg, resources);
        }
    }

    // ═════════════════════════════════════════════════════════════════
    // MODE 2 — POST-APPLICATION
    // ═════════════════════════════════════════════════════════════════

    private static void runPostApplicationMode(String pkg) {
        log("Post-application injection for " + pkg);

        try {
            Application app = currentApplication();
            if (app == null) {
                log("Application not available yet — cannot install hooks.");
                writeStatus("ERROR", "no Application");
                return;
            }

            String appPkg = app.getPackageName();
            if (appPkg != null && !appPkg.equals(pkg)) {
                log("Application package is " + appPkg + ", expected " + pkg);
            }

            ClassLoader appLoader = app.getClassLoader();

            List<ModuleInfo> modules = loadApplicableModules(pkg);
            if (modules.isEmpty()) {
                log("No applicable modules for " + pkg);
                writeStatus("NO_MODULES", pkg);
                return;
            }

            ApplicationInfo appInfo = null;
            try {
                appInfo = app.getApplicationInfo();
            } catch (Throwable ignored) {}

            XC_LoadPackage.LoadPackageParam param =
                buildLoadPackageParam(pkg, appLoader, appInfo);

            int loaded = 0;
            for (ModuleInfo m : modules) {
                if (loadModule(m, app, appLoader, param)) loaded++;
            }

            try {
                Resources appResources = app.getResources();
                if (appResources != null) {
                    callInitPackageResources(pkg, appResources);
                } else {
                    log("No Resources object on Application");
                }
            } catch (Throwable t) {
                log("Resource hooking setup failed: " + t.getMessage());
            }

            log("Injection complete: " + loaded + " module(s) loaded into " + pkg
                + " (pid=" + myPid + ")");
            writeStatus("HOOKED", pkg + "|pid=" + myPid + "|modules=" + loaded
                + "|bootstrap=false");

            if (loaded > 0) {
                writeHookedMarker(pkg, loaded, "post-application");
            }

        } catch (Throwable t) {
            log("runPostApplicationMode failed: " + t);
            t.printStackTrace();
            try { writeStatus("ERROR", String.valueOf(t.getMessage())); }
            catch (Throwable ignored) {}
        }
    }

    // ═════════════════════════════════════════════════════════════════
    // HOOKED MARKER
    // ═════════════════════════════════════════════════════════════════

    /**
     * Write a marker file recording this hook session. The file is
     * read by ModuleStatusProvider (running in the manager app) to
     * answer LSPosedManager.isModuleActive(...) queries from a
     * module's own UI process.
     *
     * Uses org.json so the writer and the reader agree on the
     * schema, and so that module package names containing characters
     * that need escaping are handled correctly.
     *
     * The marker now also records which native engines loaded, so
     * the manager can surface "Amiru active in this process" without
     * having to query the shell side separately.
     */
    private static void writeHookedMarker(String pkg, int modulesLoaded, String mode) {
        try {
            File dir = new File(HOOKED_DIR);
            if (!dir.exists()) dir.mkdirs();

            File f = new File(dir, pkg + ".json");

            JSONArray list = new JSONArray();
            synchronized (loadedModuleNames) {
                for (String name : loadedModuleNames) {
                    if (name != null) list.put(name);
                }
            }

            JSONObject engines = new JSONObject();
            engines.put("shizuposed", shizuposedLoaded);
            engines.put("amiru", amiruLoaded);

            JSONObject obj = new JSONObject();
            obj.put("pkg", pkg);
            obj.put("pid", myPid);
            obj.put("uid", myUid);
            obj.put("modules", modulesLoaded);
            obj.put("mode", mode == null ? "" : mode);
            obj.put("ts", System.currentTimeMillis());
            obj.put("moduleList", list);
            obj.put("engines", engines);

            String json = obj.toString();

            try (FileWriter w = new FileWriter(f, false)) {
                w.write(json);
            }
            try { f.setReadable(true, false); } catch (Throwable ignored) {}

            log("Wrote hooked marker: " + f.getAbsolutePath()
                + " (" + modulesLoaded + " modules, mode=" + mode
                + ", shizuposed=" + shizuposedLoaded
                + ", amiru=" + amiruLoaded
                + ", list=" + list + ")");
        } catch (Throwable t) {
            log("writeHookedMarker failed: " + t.getMessage());
        }
    }

    // ═════════════════════════════════════════════════════════════════
    // MODULE DISCOVERY
    // ═════════════════════════════════════════════════════════════════

    private static List<ModuleInfo> loadApplicableModules(String pkg) {
        List<ModuleInfo> result = new ArrayList<>();

        File dir = new File(MODULES_DIR);
        if (!dir.exists()) {
            log("Modules dir missing: " + MODULES_DIR);
            return result;
        }

        File[] files = dir.listFiles();
        if (files == null) return result;

        for (File f : files) {
            if (!f.getName().endsWith(".json")) continue;
            try {
                String json = readAll(f);
                if (json == null) continue;

                ModuleInfo info = parseModule(json);
                if (info == null) continue;
                if (!info.enabled) continue;
                if (!shouldHookApp(info, pkg)) continue;

                if (info.cachedDexPath == null || !new File(info.cachedDexPath).exists()) {
                    log("Skipping module " + info.packageName + " — dex missing");
                    continue;
                }

                result.add(info);
            } catch (Throwable t) {
                log("parse failed for " + f.getName() + ": " + t.getMessage());
            }
        }
        return result;
    }

    private static boolean shouldHookApp(ModuleInfo module, String pkg) {
        if (!module.enabled) return false;
        if (module.hookAllApps) return true;
        if (module.hookSystemApps && isSystemPackage(pkg)) return true;
        return module.hookedApps != null && module.hookedApps.contains(pkg);
    }

    private static boolean isSystemPackage(String pkg) {
        return pkg.startsWith("android.")
            || pkg.startsWith("com.android.")
            || pkg.startsWith("com.google.android.");
    }

    // ═════════════════════════════════════════════════════════════════
    // MODULE LOADING
    // ═════════════════════════════════════════════════════════════════

    private static boolean loadModule(ModuleInfo info,
                                      Application app,
                                      ClassLoader appLoader,
                                      XC_LoadPackage.LoadPackageParam param) {
        try {
            log("Loading module: " + info.packageName);

            String optDir;
            if (app != null && app.getCacheDir() != null) {
                optDir = app.getCacheDir().getAbsolutePath();
            } else {
                optDir = BASE_DIR + "/dexopt/" + info.packageName;
                new File(optDir).mkdirs();
            }

            DexClassLoader loader = new DexClassLoader(
                info.cachedDexPath,
                optDir,
                null,
                appLoader);

            String entry = info.xposedInit;
            if (entry == null || entry.isEmpty()) {
                entry = readXposedInitFromZip(info.cachedDexPath);
            }
            if (entry == null || entry.isEmpty()) {
                entry = guessEntryPoint(loader, info.packageName);
            }
            if (entry == null) {
                log("No entry point found for " + info.packageName);
                return false;
            }

            Class<?> moduleClass = loader.loadClass(entry);
            Constructor<?> ctor = moduleClass.getDeclaredConstructor();
            HiddenApiBypass.forceAccessible(ctor);
            Object instance = ctor.newInstance();

            tryEnableModuleDebug(moduleClass);

            boolean handled = false;

            if (instance instanceof IXposedHookLoadPackage) {
                log("Calling handleLoadPackage on "
                    + instance.getClass().getName()
                    + " for pkg=" + param.packageName);
                ((IXposedHookLoadPackage) instance).handleLoadPackage(param);
                handled = true;
            } else {
                for (Method m : moduleClass.getDeclaredMethods()) {
                    if (!m.getName().equals("handleLoadPackage")) continue;
                    if (m.getParameterCount() != 1) continue;
                    HiddenApiBypass.forceAccessible(m);
                    log("Calling handleLoadPackage (reflective) on "
                        + moduleClass.getName() + " for pkg=" + param.packageName);
                    m.invoke(instance, (Object) param);
                    handled = true;
                    break;
                }
            }

            if (handled) {
                moduleInstances.put(info.packageName, instance);

                synchronized (loadedModuleNames) {
                    if (!loadedModuleNames.contains(info.packageName)) {
                        loadedModuleNames.add(info.packageName);
                    }
                }

                log("Module loaded: " + info.packageName
                    + " via " + entry
                    + " (handleLoadPackage returned normally)");
            } else {
                log("Module has no handleLoadPackage: " + info.packageName);
            }
            return handled;

        } catch (Throwable t) {
            log("loadModule failed for " + info.packageName + ": " + t);
            t.printStackTrace();
            return false;
        }
    }

    /**
     * Read assets/xposed_init from the module's cached APK. This is
     * the standard Xposed entry-point declaration. Handles modules
     * whose entry class is not one of the eight conventionally-named
     * candidates in guessEntryPoint().
     */
    private static String readXposedInitFromZip(String dexPath) {
        if (dexPath == null) return null;
        File f = new File(dexPath);
        if (!f.exists()) return null;
        try (ZipFile zip = new ZipFile(f)) {
            ZipEntry entry = zip.getEntry("assets/xposed_init");
            if (entry == null) return null;
            try (InputStream is = zip.getInputStream(entry);
                 BufferedReader r = new BufferedReader(new InputStreamReader(is))) {
                String line = r.readLine();
                if (line != null && !line.isEmpty()) {
                    return line.trim();
                }
            }
        } catch (Throwable t) {
            log("readXposedInitFromZip(" + dexPath + ") failed: " + t.getMessage());
        }
        return null;
    }

    private static void tryEnableModuleDebug(Class<?> moduleClass) {
        String[] names = {"DEBUG", "debug", "LOGGING", "VERBOSE"};
        for (String name : names) {
            try {
                Field f = moduleClass.getDeclaredField(name);
                if (f.getType() != boolean.class) continue;
                HiddenApiBypass.forceAccessible(f);
                f.setBoolean(null, true);
                log("Enabled module debug flag: " + name
                    + " on " + moduleClass.getSimpleName());
                return;
            } catch (NoSuchFieldException ignored) {
            } catch (Throwable t) {
                log("Could not enable debug flag " + name + ": " + t.getMessage());
            }
        }
    }

    private static String guessEntryPoint(DexClassLoader loader, String pkg) {
        String[] candidates = {
            pkg + ".MainHook",
            pkg + ".XposedMain",
            pkg + ".Hook",
            pkg + ".XposedEntry",
            pkg + ".XposedModule",
            pkg + ".Module",
            pkg + ".Main",
            pkg + ".XposedInit"
        };
        for (String c : candidates) {
            try {
                loader.loadClass(c);
                return c;
            } catch (Throwable ignored) {}
        }
        return null;
    }

    // ═════════════════════════════════════════════════════════════════
    // APPLICATION / CONTEXT
    // ═════════════════════════════════════════════════════════════════

    private static Application currentApplication() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Method currentApplication = at.getMethod("currentApplication");
            HiddenApiBypass.forceAccessible(currentApplication);
            Object result = currentApplication.invoke(null);
            return (Application) result;
        } catch (Throwable t) {
            log("currentApplication() failed: " + t.getMessage());
            return null;
        }
    }

    private static String currentProcessName() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Method m = at.getMethod("currentProcessName");
            HiddenApiBypass.forceAccessible(m);
            Object v = m.invoke(null);
            if (v instanceof String) return (String) v;
        } catch (Throwable ignored) {}
        return targetPackage;
    }

    // ═════════════════════════════════════════════════════════════════
    // REFLECTION HELPERS
    // ═════════════════════════════════════════════════════════════════

    private static Method findMethodAny(Class<?> clazz,
                                        String name,
                                        Class<?>[]... signatures) {
        for (Class<?>[] sig : signatures) {
            try {
                Method m = clazz.getDeclaredMethod(name, sig);
                HiddenApiBypass.forceAccessible(m);
                return m;
            } catch (NoSuchMethodException ignored) {
            } catch (Throwable t) {
                log("findMethodAny(" + name + ") error: " + t.getMessage());
            }
        }
        return null;
    }

    private static void setFieldAny(Object target, Object value, String... names) {
        if (target == null) return;
        for (String name : names) {
            if (setFieldQuiet(target, name, value)) return;
        }
        log("setFieldAny: none of " + java.util.Arrays.toString(names)
            + " exist on " + target.getClass());
    }

    private static boolean setFieldQuiet(Object target, String name, Object value) {
        try {
            Class<?> c = target.getClass();
            while (c != null) {
                try {
                    Field f = c.getDeclaredField(name);
                    HiddenApiBypass.forceAccessible(f);
                    f.set(target, value);
                    return true;
                } catch (NoSuchFieldException e) {
                    c = c.getSuperclass();
                }
            }
        } catch (Throwable t) {
            log("setFieldQuiet(" + name + ") failed: " + t.getMessage());
        }
        return false;
    }

    // ═════════════════════════════════════════════════════════════════
    // JSON PARSING
    // ═════════════════════════════════════════════════════════════════

    private static ModuleInfo parseModule(String json) {
        try {
            JSONObject o = new JSONObject(json);
            ModuleInfo i = new ModuleInfo();
            i.packageName    = o.optString("packageName", null);
            i.name           = o.optString("name", null);
            i.xposedInit     = o.optString("xposedInit", null);
            i.cachedDexPath  = o.optString("cachedDexPath", null);
            i.enabled        = o.optBoolean("enabled", false);
            i.hookAllApps    = o.optBoolean("hookAllApps", false);
            i.hookSystemApps = o.optBoolean("hookSystemApps", false);

            JSONArray apps = o.optJSONArray("hookedApps");
            if (apps != null) {
                for (int k = 0; k < apps.length(); k++) {
                    String a = apps.optString(k, null);
                    if (a != null && !a.isEmpty()) i.hookedApps.add(a);
                }
            }

            if (i.packageName == null || i.packageName.isEmpty()) return null;
            return i;
        } catch (Throwable t) {
            log("parseModule failed: " + t.getMessage());
            return null;
        }
    }

    // ═════════════════════════════════════════════════════════════════
    // FILE + LOGGING
    // ═════════════════════════════════════════════════════════════════

    private static void initDirectories() {
        try {
            new File(BASE_DIR).mkdirs();
            new File(MODULES_DIR).mkdirs();
            new File(HOOKED_DIR).mkdirs();
            new File(LIBS_DIR).mkdirs();
        } catch (Throwable ignored) {}
    }

    private static String readAll(File f) {
        if (f == null || !f.exists()) return null;
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
            return sb.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    private static void log(String msg) {
        String line = "[" + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
            .format(new Date()) + "] [pid " + Process.myPid() + "] " + msg;

        System.out.println(line);

        try {
            File f = new File(LOG_FILE);
            File parent = f.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            try (FileWriter w = new FileWriter(f, true)) {
                w.write(line);
                w.write('\n');
            }
        } catch (Throwable ignored) {}
    }

    private static void logBox(String title, String sub) {
        log("── " + title + " ──");
        log(sub);
    }

    private static void writeStatus(String status, String msg) {
        try {
            File f = new File(STATUS_FILE);
            File parent = f.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            try (FileWriter w = new FileWriter(f, false)) {
                w.write(status + "|" + System.currentTimeMillis() + "|" + msg);
            }
        } catch (Throwable ignored) {}
    }
}