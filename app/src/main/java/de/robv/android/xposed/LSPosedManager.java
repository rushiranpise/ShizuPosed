package de.robv.android.xposed;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.util.Log;

/**
 * LSPosed-compatible manager API shim.
 *
 * Modules written for LSPosed call these methods to discover which
 * framework they're running under and to query per-module state. By
 * providing the same class name and method signatures, ShizuPosed
 * makes those modules behave as if they were running under LSPosed.
 *
 * All answers are backed by ShizuPosed's ModuleStatusProvider
 * (content://com.shizuposed.manager.status).
 *
 * Two distinct questions modules ask:
 *   • isModuleEnabled(pkg) — did the user turn this module on?
 *   • isModuleActive(pkg)  — has the module actually loaded into at
 *                            least one target process at least once?
 *
 * Hide My Applist and similar modules use isModuleActive to decide
 * whether to display "Activated" in their own UI. That state is
 * derived from the shell-side hooked markers written by XposedHook,
 * not from the manager's module list, so it reflects what the
 * framework has actually done.
 *
 * CONTEXT RESOLUTION
 * ------------------
 * ActivityThread.currentApplication() reliably returns the
 * Application only on the main thread. Module UIs frequently call
 * isModuleActive() from background threads, WorkManager workers,
 * ContentProvider.onCreate, etc., where it can return null.
 *
 * This class therefore tries three sources in order:
 *   1. ActivityThread.currentApplication()
 *   2. ActivityThread.currentActivityThread().getSystemContext()
 *   3. A static fallback context registered via setFallbackContext()
 *
 * The static fallback is populated automatically by XposedBridge.log()
 * on first use, since log() runs in a context where the caller usually
 * has a valid Application.
 */
public final class LSPosedManager {

    /** Framework name reported to modules. */
    public static final String FRAMEWORK_NAME = "ShizuPosed";

    /** Reported API version. Matches XposedBridge.XPOSED_BRIDGE_VERSION. */
    public static final int API_VERSION = 93;

    /** Authority of ShizuPosed's ModuleStatusProvider. */
    private static final String PROVIDER_AUTHORITY = "com.shizuposed.manager.status";

    private static final String LOG_TAG = "ShizuPosed";

    /** Last-resort context, populated by setFallbackContext(). */
    private static volatile Context sFallbackContext;

    private LSPosedManager() {}

    // ═════════════════════════════════════════════════════════════
    // FALLBACK CONTEXT
    // ═════════════════════════════════════════════════════════════

    /**
     * Register a context to use when ActivityThread lookups fail.
     * Called automatically by XposedBridge.log() so that later
     * off-main-thread queries still have a usable Context.
     */
    public static void setFallbackContext(Context ctx) {
        if (ctx != null) {
            Context app = ctx.getApplicationContext();
            sFallbackContext = app != null ? app : ctx;
        }
    }

    /**
     * Accessor for the shared fallback context.
     *
     * XposedBridge uses this as the last step in its own context
     * resolution, so both shims agree on which Context is in play
     * when ActivityThread lookups fail.
     *
     * @return the registered fallback Context, or null if none has
     *         been set yet.
     */
    public static Context getFallbackContext() {
        return sFallbackContext;
    }

    // ═════════════════════════════════════════════════════════════
    // FRAMEWORK IDENTITY
    // ═════════════════════════════════════════════════════════════

    public static boolean isManagerInstalled() {
        return true;
    }

    public static boolean isManagerHidden() {
        return false;
    }

    public static String getFrameworkName() {
        return FRAMEWORK_NAME;
    }

    public static int getApiVersion() {
        return API_VERSION;
    }

    public static int getVersionCode() {
        return API_VERSION;
    }

    public static String getVersionName() {
        return "3.9";
    }

    public static String getManagerPackageName() {
        return "com.shizuposed.manager";
    }

    // ═════════════════════════════════════════════════════════════
    // ENABLED STATE
    // ═════════════════════════════════════════════════════════════

    public static boolean isModuleEnabled(String packageName) {
        Context ctx = currentApplication();
        if (ctx == null) return false;
        return isModuleEnabled(ctx, packageName);
    }

    public static boolean isModuleEnabled(Context context, String packageName) {
        if (context == null || packageName == null) return false;
        try {
            ContentResolver cr = context.getContentResolver();
            Uri uri = Uri.parse("content://" + PROVIDER_AUTHORITY
                + "/module/" + packageName);
            try (Cursor c = cr.query(uri, null, null, null, null)) {
                if (c != null && c.moveToFirst()) {
                    int idx = c.getColumnIndex("enabled");
                    if (idx != -1) return c.getInt(idx) == 1;
                    int vIdx = c.getColumnIndex("value");
                    if (vIdx != -1) return "1".equals(c.getString(vIdx));
                }
            }
        } catch (Throwable t) {
            Log.e(LOG_TAG, "isModuleEnabled(" + packageName + ") failed", t);
        }
        return false;
    }

    public static String[] getEnabledModules() {
        Context ctx = currentApplication();
        if (ctx == null) return new String[0];
        try {
            ContentResolver cr = ctx.getContentResolver();
            Uri uri = Uri.parse("content://" + PROVIDER_AUTHORITY + "/modules");
            try (Cursor c = cr.query(uri, null, null, null, null)) {
                if (c == null) return new String[0];
                int idx = c.getColumnIndex("package");
                if (idx == -1) return new String[0];

                // Two passes: first count non-null rows, then fill.
                // Avoids inserting nulls into the returned array.
                int count = 0;
                c.moveToPosition(-1);
                while (c.moveToNext()) {
                    if (c.getString(idx) != null) count++;
                }
                String[] out = new String[count];
                c.moveToPosition(-1);
                int i = 0;
                while (c.moveToNext()) {
                    String pkg = c.getString(idx);
                    if (pkg != null) out[i++] = pkg;
                }
                return out;
            }
        } catch (Throwable t) {
            Log.e(LOG_TAG, "getEnabledModules failed", t);
            return new String[0];
        }
    }

    // ═════════════════════════════════════════════════════════════
    // ACTIVE STATE
    // ═════════════════════════════════════════════════════════════

    /**
     * Has the module loaded into at least one target process?
     *
     * Reads the shell-side hooked markers via
     * content://.../active/<pkg>. Returns false if no marker lists
     * this module's package name.
     *
     * Modules that display "Activated" in their own UI rely on this.
     * If the module has never been launched under ShizuPosed for any
     * target in its scope, this returns false even though the module
     * is enabled. That's the correct answer — the module hasn't
     * actually done anything yet.
     */
    public static boolean isModuleActive(String modulePackage) {
        Context ctx = currentApplication();
        if (ctx == null) {
            Log.w(LOG_TAG, "isModuleActive(" + modulePackage
                + "): no Context available (off-main-thread?)");
            return false;
        }
        return isModuleActive(ctx, modulePackage);
    }

    public static boolean isModuleActive(Context context, String modulePackage) {
        if (context == null || modulePackage == null) return false;
        try {
            ContentResolver cr = context.getContentResolver();
            Uri uri = Uri.parse("content://" + PROVIDER_AUTHORITY
                + "/active/" + modulePackage);
            try (Cursor c = cr.query(uri, null, null, null, null)) {
                if (c != null && c.moveToFirst()) {
                    int idx = c.getColumnIndex("active");
                    if (idx != -1) return c.getInt(idx) == 1;
                    int vIdx = c.getColumnIndex("value");
                    if (vIdx != -1) return "1".equals(c.getString(vIdx));
                }
            }
        } catch (Throwable t) {
            Log.e(LOG_TAG, "isModuleActive(" + modulePackage + ") failed", t);
        }
        return false;
    }

    /**
     * Packages the module has loaded into.
     *
     * Returns every target package whose shell-side hooked marker
     * lists this module in its moduleList.
     */
    public static String[] getModuleScope(String modulePackage) {
        Context ctx = currentApplication();
        if (ctx == null) return new String[0];
        return getModuleScope(ctx, modulePackage);
    }

    public static String[] getModuleScope(Context context, String modulePackage) {
        if (context == null || modulePackage == null) return new String[0];
        try {
            ContentResolver cr = context.getContentResolver();
            Uri uri = Uri.parse("content://" + PROVIDER_AUTHORITY
                + "/scope/" + modulePackage);
            try (Cursor c = cr.query(uri, null, null, null, null)) {
                if (c == null) return new String[0];
                int idx = c.getColumnIndex("package");
                if (idx == -1) return new String[0];

                int count = 0;
                c.moveToPosition(-1);
                while (c.moveToNext()) {
                    if (c.getString(idx) != null) count++;
                }
                String[] out = new String[count];
                c.moveToPosition(-1);
                int i = 0;
                while (c.moveToNext()) {
                    String pkg = c.getString(idx);
                    if (pkg != null) out[i++] = pkg;
                }
                return out;
            }
        } catch (Throwable t) {
            Log.e(LOG_TAG, "getModuleScope(" + modulePackage + ") failed", t);
            return new String[0];
        }
    }

    // ═════════════════════════════════════════════════════════════
    // CONTEXT RESOLUTION
    // ═════════════════════════════════════════════════════════════

    /**
     * Resolve a Context, working off the main thread.
     *
     * Order:
     *   1. ActivityThread.currentApplication()
     *   2. ActivityThread.currentActivityThread().getSystemContext()
     *   3. sFallbackContext
     */
    private static Context currentApplication() {
        // Fast path: cached fallback
        Context fb = sFallbackContext;

        try {
            Class<?> at = Class.forName("android.app.ActivityThread");

            // 1. currentApplication()
            try {
                java.lang.reflect.Method m = at.getMethod("currentApplication");
                Object o = m.invoke(null);
                if (o instanceof Context) {
                    Context app = (Context) o;
                    sFallbackContext = app;
                    return app;
                }
            } catch (Throwable ignored) {}

            // 2. currentActivityThread().getSystemContext()
            try {
                java.lang.reflect.Method cur = at.getMethod("currentActivityThread");
                Object thread = cur.invoke(null);
                if (thread != null) {
                    java.lang.reflect.Method getSys = at.getMethod("getSystemContext");
                    Object sys = getSys.invoke(thread);
                    if (sys instanceof Context) {
                        return (Context) sys;
                    }
                }
            } catch (Throwable ignored) {}

        } catch (Throwable t) {
            Log.e(LOG_TAG, "currentApplication: reflective lookup failed", t);
        }

        return fb;
    }
}