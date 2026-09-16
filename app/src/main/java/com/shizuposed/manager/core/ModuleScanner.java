package com.shizuposed.manager.core;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

import com.shizuposed.manager.model.ModuleInfo;
import com.shizuposed.manager.utils.Logger;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * ModuleScanner
 *
 * Enumerates every installed package and checks whether its APK
 * contains assets/xposed_init — the canonical marker of an Xposed
 * module. Modules found this way are registered with ModuleLoader so
 * they appear in the Modules tab without the user having to add them
 * manually.
 *
 * Also removes stale module entries: any registered module whose APK
 * has disappeared from disk AND whose package is no longer installed
 * is purged. This handles the case where the user uninstalls a module
 * from Settings → Apps instead of from the Modules tab.
 *
 * Skips:
 *   • Our own package (hooking ShizuPosed from ShizuPosed is useless)
 *   • Packages already in the ModuleLoader cache with the same apkPath
 *     (avoids needless rescans)
 *
 * hasUi
 * -----
 * Each detected module is tagged with hasUi = true if its APK
 * declares a launcher activity. The Modules tab does NOT filter on
 * this flag — every module is shown. The flag is consumed only by
 * the scope editor, which uses it to decide whether a module can
 * select its own package as a hook target.
 */
public final class ModuleScanner {

    private static final String TAG = "ModuleScanner";

    private ModuleScanner() {}

    /** Result of a scan. */
    public static final class ScanResult {
        public int installedPackages = 0;
        public int xposedModulesFound = 0;
        public int newlyRegistered = 0;
        public int purgedCount = 0;
        public final List<String> newlyRegisteredPackages = new ArrayList<>();
        public final List<String> purgedPackages = new ArrayList<>();
    }

    /**
     * Run a scan. Cleans up stale modules, then discovers any newly
     * installed Xposed modules. Safe to call repeatedly.
     */
    public static ScanResult scanInstalledModules(Context context) {
        ScanResult result = new ScanResult();
        Logger logger = Logger.getInstance(context);
        PackageManager pm = context.getPackageManager();
        ModuleLoader loader = ModuleLoader.getInstance(context);
        String self = context.getPackageName();

        // ── 1. Purge stale entries first ────────────────────────────
        try {
            result.purgedCount = purgeUninstalledModules(context, result.purgedPackages);
        } catch (Throwable t) {
            logger.w("[" + TAG + "] Purge pass failed: " + t.getMessage());
        }

        // ── 2. Discovery pass over installed packages ───────────────
        List<ApplicationInfo> installed;
        try {
            installed = pm.getInstalledApplications(
                PackageManager.GET_META_DATA | PackageManager.MATCH_DISABLED_COMPONENTS
            );
        } catch (Throwable t) {
            logger.e("[" + TAG + "] getInstalledApplications failed: " + t.getMessage());
            return result;
        }
        if (installed == null) return result;

        // Snapshot of currently-registered modules keyed by package name
        Set<String> alreadyRegistered = new HashSet<>();
        for (ModuleInfo m : loader.getCachedModules()) {
            if (m != null && m.packageName != null) {
                alreadyRegistered.add(m.packageName);
            }
        }

        for (ApplicationInfo ai : installed) {
            if (ai == null || ai.packageName == null) continue;
            if (self.equals(ai.packageName)) continue;

            result.installedPackages++;

            try {
                String apkPath = ai.sourceDir;
                if (apkPath == null) continue;

                // If already registered and the APK hasn't changed, skip.
                if (alreadyRegistered.contains(ai.packageName)) {
                    ModuleInfo existing = loader.getModule(ai.packageName);
                    if (existing != null && apkPath.equals(existing.apkPath)) {
                        continue;
                    }
                }

                String entry = readXposedInit(apkPath);
                if (entry == null) continue;

                result.xposedModulesFound++;

                ModuleInfo module = new ModuleInfo();
                module.packageName = ai.packageName;
                module.name = ai.loadLabel(pm).toString();
                module.version = readVersion(pm, ai.packageName);
                module.xposedInit = entry;
                module.apkPath = apkPath;
                module.enabled = existingOrEnabled(loader, ai.packageName);
                module.hookedApps = preserveScope(loader, ai.packageName);

                // Tag with hasUi. Consumed by the scope editor only.
                module.hasUi = hasLauncherActivity(context, ai.packageName);

                boolean ok = loader.installModule(module);
                if (ok) {
                    result.newlyRegistered++;
                    result.newlyRegisteredPackages.add(ai.packageName);
                    logger.i("[" + TAG + "] Auto-detected module: "
                        + ai.packageName + " (" + entry + ", hasUi=" + module.hasUi + ")");
                }

            } catch (Throwable t) {
                logger.d("[" + TAG + "] Skipping " + ai.packageName + ": " + t.getMessage());
            }
        }

        logger.i("[" + TAG + "] Scan complete: "
            + result.installedPackages + " installed packages checked, "
            + result.xposedModulesFound + " new Xposed modules found, "
            + result.newlyRegistered + " registered, "
            + result.purgedCount + " purged");

        return result;
    }

    // ═════════════════════════════════════════════════════════════
    // UI DETECTION
    // ═════════════════════════════════════════════════════════════

    /**
     * Does the given package declare a launcher activity?
     *
     * Two-step check:
     *   1. queryIntentActivities with MATCH_DEFAULT_ONLY — the
     *      canonical launcher query. Catches normal launcher icons.
     *   2. getLaunchIntentForPackage — catches activity aliases and
     *      leanback-launcher entries the query above can miss.
     *
     * Step 2 is what makes modules like Hide My Applist detectable:
     * they sometimes declare their settings activity via an alias
     * that the strict MATCH_DEFAULT_ONLY filter rejects.
     *
     * Returns false on any error. Conservative default is "headless",
     * which suppresses the self-scope entry in the scope editor.
     */
    public static boolean hasLauncherActivity(Context context, String pkg) {
        if (context == null || pkg == null) return false;
        try {
            PackageManager pm = context.getPackageManager();

            // Step 1: explicit launcher intent query
            Intent intent = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setPackage(pkg);
            List<ResolveInfo> activities = pm.queryIntentActivities(
                intent, PackageManager.MATCH_DEFAULT_ONLY);
            if (activities != null && !activities.isEmpty()) return true;

            // Step 2: fallback for aliases / leanback
            Intent launch = pm.getLaunchIntentForPackage(pkg);
            return launch != null && launch.getComponent() != null;
        } catch (Throwable t) {
            return false;
        }
    }

    // ═════════════════════════════════════════════════════════════
    // PURGE STALE MODULES
    // ═════════════════════════════════════════════════════════════

    public static int purgeUninstalledModules(Context context,
                                              List<String> purgedOut) {
        Logger logger = Logger.getInstance(context);
        PackageManager pm = context.getPackageManager();
        ModuleLoader loader = ModuleLoader.getInstance(context);

        int removed = 0;
        List<ModuleInfo> snapshot = new ArrayList<>(loader.getCachedModules());

        for (ModuleInfo m : snapshot) {
            if (m == null || m.packageName == null) continue;

            boolean apkExists = false;
            if (m.apkPath != null) {
                try {
                    apkExists = new File(m.apkPath).exists();
                } catch (Throwable ignored) {}
            }

            boolean packageInstalled = false;
            try {
                pm.getPackageInfo(m.packageName, 0);
                packageInstalled = true;
            } catch (PackageManager.NameNotFoundException ignored) {
            } catch (Throwable ignored) {}

            if (apkExists || packageInstalled) continue;

            try {
                boolean ok = loader.uninstallModule(m.packageName);
                if (ok) {
                    removed++;
                    if (purgedOut != null) purgedOut.add(m.packageName);
                    logger.i("[" + TAG + "] Purged uninstalled module: "
                        + m.packageName);
                }
            } catch (Throwable t) {
                logger.w("[" + TAG + "] Failed to purge " + m.packageName
                    + ": " + t.getMessage());
            }
        }

        if (removed > 0) {
            logger.i("[" + TAG + "] Purge complete: removed " + removed + " module(s)");
        }
        return removed;
    }

    public static int purgeUninstalledModules(Context context) {
        return purgeUninstalledModules(context, null);
    }

    // ═════════════════════════════════════════════════════════════
    // HELPERS
    // ═════════════════════════════════════════════════════════════

    private static String readXposedInit(String apkPath) {
        try (ZipFile zip = new ZipFile(apkPath)) {
            ZipEntry entry = zip.getEntry("assets/xposed_init");
            if (entry == null) return null;

            try (InputStream is = zip.getInputStream(entry);
                 BufferedReader r = new BufferedReader(new InputStreamReader(is))) {
                String line;
                while ((line = r.readLine()) != null) {
                    String trimmed = line.trim();
                    if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                        return trimmed;
                    }
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static String readVersion(PackageManager pm, String pkg) {
        try {
            android.content.pm.PackageInfo pi = pm.getPackageInfo(pkg, 0);
            return pi != null ? pi.versionName : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean existingOrEnabled(ModuleLoader loader, String pkg) {
        ModuleInfo existing = loader.getModule(pkg);
        if (existing != null) return existing.enabled;
        return true;
    }

    private static Set<String> preserveScope(ModuleLoader loader, String pkg) {
        ModuleInfo existing = loader.getModule(pkg);
        if (existing != null && existing.hookedApps != null) {
            return new HashSet<>(existing.hookedApps);
        }
        return new HashSet<>();
    }
}