package com.shizuposed.manager.core;

import android.content.Context;
import android.content.pm.PackageManager;

import androidx.annotation.Nullable;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.shizuposed.manager.model.ModuleInfo;
import com.shizuposed.manager.service.ShizuPosedService;
import com.shizuposed.manager.utils.FileUtils;
import com.shizuposed.manager.utils.Logger;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * ModuleLoader
 *
 * Responsible for:
 *   • Reading installed module descriptors (JSON)
 *   • Installing / uninstalling / saving modules
 *   • Scanning APKs for entry points (assets/xposed_init) and caching the
 *     module dex so XposedHook can load it from the shell side.
 *
 * Storage layout note:
 *
 *   The module JSONs live in app-internal storage (getFilesDir).
 *   The module .dex copies live in EXTERNAL app storage so shell can
 *   read them; shell cannot read /data/user/0/<our-pkg>/files/.
 *
 * hasUi
 * -----
 * The Modules tab shows every module regardless of hasUi. The flag is
 * consumed only by ModulesFragment's scope editor.
 *
 * loadModules() self-heals:
 *   • If moduleDir is missing or is a file, it is recreated.
 *   • If hasUi is false on disk, it is re-checked against the
 *     PackageManager and corrected if wrong.
 */
public class ModuleLoader {
    private static final String TAG = "ModuleLoader";
    private static ModuleLoader instance;

    private final Context context;
    private final Logger logger;
    private final Gson gson;

    private final File moduleDir;
    private final File dexCacheDir;
    private final File legacyDexCacheDir;

    private final ConcurrentHashMap<String, ModuleInfo> loadedModules = new ConcurrentHashMap<>();

    private ModuleLoader(Context context) {
        this.context = context.getApplicationContext();
        this.logger = Logger.getInstance(context);
        this.gson = new Gson();

        this.moduleDir = new File(context.getFilesDir(), ".syscall_cache/modules");

        File external = context.getExternalFilesDir(null);
        File dexBase = external != null ? external : context.getFilesDir();
        this.dexCacheDir = new File(dexBase, ".syscall_cache");

        this.legacyDexCacheDir = new File(context.getFilesDir(), ".syscall_cache");

        ensureDir(moduleDir);
        ensureDir(dexCacheDir);

        migrateLegacyDexFiles();
    }

    public static synchronized ModuleLoader getInstance(Context context) {
        if (instance == null) {
            instance = new ModuleLoader(context);
        }
        return instance;
    }

    /**
     * Create a directory if it doesn't exist, logging the result.
     * mkdirs() can silently fail if the parent exists as a file, or if
     * the path is unwritable. Logging makes that visible.
     */
    private void ensureDir(File dir) {
        if (dir == null) return;
        if (dir.exists() && dir.isDirectory()) return;
        if (dir.exists() && !dir.isDirectory()) {
            logger.w("ensureDir: " + dir.getAbsolutePath()
                + " exists as a file, deleting");
            try { dir.delete(); } catch (Throwable ignored) {}
        }
        boolean ok = dir.mkdirs();
        if (logger != null) {
            logger.i("ensureDir: " + dir.getAbsolutePath()
                + " created=" + ok
                + " exists=" + dir.exists()
                + " isDir=" + dir.isDirectory());
        }
    }

    private void migrateLegacyDexFiles() {
        try {
            if (legacyDexCacheDir.equals(dexCacheDir)) return;
            File[] files = legacyDexCacheDir.listFiles();
            if (files == null) return;

            int migrated = 0;
            for (File f : files) {
                if (!f.isFile() || !f.getName().endsWith(".dex")) continue;
                File dst = new File(dexCacheDir, f.getName());
                if (dst.exists() && dst.length() == f.length()) continue;

                try (FileInputStream in = new FileInputStream(f);
                     FileOutputStream out = new FileOutputStream(dst)) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    out.flush();
                }
                dst.setReadable(true, false);
                migrated++;
            }
            if (migrated > 0) {
                logger.i("Migrated " + migrated + " dex file(s) to external storage");
            }
        } catch (Throwable t) {
            logger.w("migrateLegacyDexFiles: " + t.getMessage());
        }
    }

    // ═════════════════════════════════════════════════════════════════
    // LOAD
    // ═════════════════════════════════════════════════════════════════

    public List<ModuleInfo> loadModules() {
        List<ModuleInfo> modules = new ArrayList<>();

        try {
            // Self-heal: if the directory is missing or is a file, recreate.
            if (!moduleDir.exists() || !moduleDir.isDirectory()) {
                logger.w("loadModules: module dir missing, recreating: "
                    + moduleDir.getAbsolutePath());
                ensureDir(moduleDir);
            }

            File[] moduleFiles = moduleDir.listFiles();

            if (logger != null) {
                logger.i("loadModules: dir=" + moduleDir.getAbsolutePath()
                    + " exists=" + moduleDir.exists()
                    + " isDir=" + moduleDir.isDirectory()
                    + " fileCount=" + (moduleFiles == null
                        ? "null" : String.valueOf(moduleFiles.length)));
            }

            if (moduleFiles != null) {
                loadedModules.clear();
                for (File file : moduleFiles) {
                    if (!file.getName().endsWith(".json")) continue;
                    try {
                        String json = FileUtils.readFile(file);
                        if (json == null) continue;
                        ModuleInfo module = gson.fromJson(json, ModuleInfo.class);
                        if (module == null || module.packageName == null) continue;

                        // ── hasUi self-heal ──────────────────────────
                        // Recompute hasUi whenever the field is missing
                        // OR currently false. A false value may be stale
                        // from an earlier build that used a weaker check.
                        boolean currentHasUi = module.hasUi;
                        if (!jsonHasField(json, "hasUi") || !currentHasUi) {
                            module.hasUi = ModuleScanner.hasLauncherActivity(
                                context, module.packageName);
                            if (module.hasUi != currentHasUi) {
                                try {
                                    FileUtils.writeFile(file, gson.toJson(module));
                                } catch (Throwable t) {
                                    logger.w("hasUi writeback failed for "
                                        + module.packageName + ": " + t.getMessage());
                                }
                                logger.i("Corrected hasUi for "
                                    + module.packageName + " -> " + module.hasUi);
                            }
                        }

                        // Fix cachedDexPath if it points at a missing file.
                        if (module.cachedDexPath == null
                                || !new File(module.cachedDexPath).exists()) {

                            File externalDex = new File(dexCacheDir, module.packageName + ".dex");
                            if (externalDex.exists()) {
                                module.cachedDexPath = externalDex.getAbsolutePath();
                            } else {
                                File legacyDex = new File(legacyDexCacheDir,
                                    module.packageName + ".dex");
                                if (legacyDex.exists()) {
                                    File dst = new File(dexCacheDir,
                                        module.packageName + ".dex");
                                    try (FileInputStream in = new FileInputStream(legacyDex);
                                         FileOutputStream out = new FileOutputStream(dst)) {
                                        byte[] buf = new byte[8192];
                                        int n;
                                        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                                        out.flush();
                                    }
                                    dst.setReadable(true, false);
                                    module.cachedDexPath = dst.getAbsolutePath();
                                }
                            }
                        }

                        if (module.cachedDexPath != null) {
                            String cp = module.cachedDexPath;
                            String internalBase = context.getFilesDir().getAbsolutePath();
                            if (cp.startsWith(internalBase)) {
                                File externalDex = new File(dexCacheDir,
                                    module.packageName + ".dex");
                                if (externalDex.exists()) {
                                    module.cachedDexPath = externalDex.getAbsolutePath();
                                }
                            }
                        }

                        loadedModules.put(module.packageName, module);
                        modules.add(module);
                    } catch (Exception e) {
                        logger.e("Failed to load module from " + file.getName()
                            + ": " + e.getMessage());
                    }
                }
            }

            if (modules.isEmpty()) {
                logger.i("No modules found");
            } else {
                logger.i("Loaded " + modules.size() + " modules");
            }
        } catch (Exception e) {
            logger.e("Failed to load modules: " + e.getMessage());
        }
        return modules;
    }

    public List<ModuleInfo> getCachedModules() {
        return new ArrayList<>(loadedModules.values());
    }

    // ═════════════════════════════════════════════════════════════════
    // INSTALL / SAVE / UNINSTALL
    // ═════════════════════════════════════════════════════════════════

    public boolean installModule(ModuleInfo module) {
        try {
            if (module == null || module.packageName == null) return false;

            // Defensive: module dir should exist, but recreate if not.
            if (!moduleDir.exists() || !moduleDir.isDirectory()) {
                ensureDir(moduleDir);
            }

            try {
                PackageManager pm = context.getPackageManager();
                android.content.pm.PackageInfo pkgInfo = pm.getPackageInfo(module.packageName, 0);
                if (pkgInfo != null) {
                    module.version = pkgInfo.versionName;
                    if (module.name == null || module.name.isEmpty()) {
                        module.name = pkgInfo.applicationInfo.loadLabel(pm).toString();
                    }
                }
            } catch (PackageManager.NameNotFoundException ignored) {
            }

            if (module.apkPath == null || !new File(module.apkPath).exists()) {
                String fromPm = findModuleApkPath(module.packageName);
                if (fromPm != null) module.apkPath = fromPm;
            }

            // Refresh hasUi on every install so module updates propagate.
            try {
                module.hasUi = ModuleScanner.hasLauncherActivity(
                    context, module.packageName);
            } catch (Throwable ignored) {}

            if (module.apkPath != null && new File(module.apkPath).exists()) {
                File cachedDex = new File(dexCacheDir, module.packageName + ".dex");
                if (!cachedDex.exists() || cachedDex.length() == 0) {
                    scanModuleForEntryPoints(module);
                } else {
                    module.cachedDexPath = cachedDex.getAbsolutePath();
                    cachedDex.setReadable(true, false);
                }
            }

            File moduleFile = new File(moduleDir, module.packageName + ".json");
            String json = gson.toJson(module);
            FileUtils.writeFile(moduleFile, json);

            if (!moduleFile.exists() || moduleFile.length() == 0) {
                logger.e("installModule: write produced no file at "
                    + moduleFile.getAbsolutePath());
                return false;
            }

            loadedModules.put(module.packageName, module);
            logger.i("Installed module: " + module.packageName
                + " (dex=" + module.cachedDexPath + ", entry=" + module.xposedInit
                + ", hasUi=" + module.hasUi + ")");
            return true;
        } catch (Exception e) {
            logger.e("Failed to install module: " + e.getMessage());
            return false;
        }
    }

    public boolean saveModule(ModuleInfo module) {
        try {
            if (module == null || module.packageName == null) return false;
            if (!moduleDir.exists() || !moduleDir.isDirectory()) {
                ensureDir(moduleDir);
            }
            File moduleFile = new File(moduleDir, module.packageName + ".json");
            String json = gson.toJson(module);
            FileUtils.writeFile(moduleFile, json);
            loadedModules.put(module.packageName, module);
            logger.i("Saved module: " + module.packageName);
            return true;
        } catch (Exception e) {
            logger.e("Failed to save module: " + e.getMessage());
            return false;
        }
    }

    public boolean uninstallModule(String packageName) {
        try {
            File moduleFile = new File(moduleDir, packageName + ".json");
            if (moduleFile.exists() && moduleFile.delete()) {
                logger.i("Deleted module file: " + moduleFile.getAbsolutePath());
            }

            ModuleInfo removed = loadedModules.remove(packageName);
            if (removed != null) {
                logger.i("Uninstalled module from cache: " + packageName);
            } else {
                logger.w("Module not found in cache: " + packageName);
            }

            File externalDex = new File(dexCacheDir, packageName + ".dex");
            if (externalDex.exists() && externalDex.delete()) {
                logger.i("Deleted cached dex: " + externalDex.getAbsolutePath());
            }
            File legacyDex = new File(legacyDexCacheDir, packageName + ".dex");
            if (legacyDex.exists() && legacyDex.delete()) {
                logger.i("Deleted legacy dex: " + legacyDex.getAbsolutePath());
            }

            return true;
        } catch (Exception e) {
            logger.e("Failed to uninstall module: " + e.getMessage());
            return false;
        }
    }

    // ═════════════════════════════════════════════════════════════════
    // QUERIES
    // ═════════════════════════════════════════════════════════════════

    public ModuleInfo getModule(String packageName) {
        return loadedModules.get(packageName);
    }

    public boolean isModuleEnabled(String packageName) {
        ModuleInfo m = loadedModules.get(packageName);
        return m != null && m.enabled;
    }

    public List<ModuleInfo> getEnabledModules() {
        List<ModuleInfo> enabled = new ArrayList<>();
        for (ModuleInfo m : loadedModules.values()) {
            if (m != null && m.enabled) enabled.add(m);
        }
        return enabled;
    }

    public int getHookedProcessCount() {
        try {
            ProcessMonitor monitor = ProcessMonitor.getInstance(context);
            return monitor.getHookedProcesses().size();
        } catch (Exception e) {
            return 0;
        }
    }

    public int getHookedAppCount(String modulePackage) {
        ModuleInfo m = loadedModules.get(modulePackage);
        if (m == null || m.hookedApps == null) return 0;
        return m.hookedApps.size();
    }

    public boolean isServiceRunning() {
        try {
            return ShizuPosedService.isServiceRunning();
        } catch (Throwable t) {
            return false;
        }
    }

    public void notifyResourceChange(String packageName, int id, Object replacement) {
        logger.i("Resource change: " + packageName + " ID: 0x" + Integer.toHexString(id));
    }

    // ═════════════════════════════════════════════════════════════════
    // APK SCAN + DEX CACHE
    // ═════════════════════════════════════════════════════════════════

    private void scanModuleForEntryPoints(ModuleInfo module) {
        if (module == null || module.apkPath == null) return;
        File apk = new File(module.apkPath);
        if (!apk.exists()) {
            logger.w("scanModuleForEntryPoints: apk missing for " + module.packageName);
            return;
        }

        try {
            File cached = new File(dexCacheDir, module.packageName + ".dex");
            if (!cached.exists() || cached.length() == 0) {
                try (FileInputStream is = new FileInputStream(apk);
                     FileOutputStream os = new FileOutputStream(cached)) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = is.read(buf)) > 0) os.write(buf, 0, n);
                    os.flush();
                }
                cached.setReadable(true, false);
                logger.i("Cached dex: " + cached.getAbsolutePath()
                    + " (" + cached.length() + " bytes)");
            }
            module.cachedDexPath = cached.getAbsolutePath();
        } catch (Exception e) {
            logger.e("Failed to cache dex for " + module.packageName + ": " + e.getMessage());
        }

        try (ZipFile zip = new ZipFile(apk)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();

                if (name.equals("assets/xposed_init")) {
                    try (InputStream is = zip.getInputStream(entry);
                         BufferedReader reader = new BufferedReader(new InputStreamReader(is))) {
                        String line = reader.readLine();
                        if (line != null && !line.isEmpty()) {
                            module.xposedInit = line.trim();
                            logger.i("Found xposed_init for " + module.packageName
                                + ": " + module.xposedInit);
                        }
                    }
                } else if (name.equals("assets/native_init")) {
                    try (InputStream is = zip.getInputStream(entry);
                         BufferedReader reader = new BufferedReader(new InputStreamReader(is))) {
                        String line = reader.readLine();
                        if (line != null && !line.isEmpty()) {
                            logger.i("Found native_init for " + module.packageName
                                + ": " + line.trim());
                        }
                    }
                }
            }
        } catch (Exception e) {
            logger.e("Failed to scan APK for " + module.packageName + ": " + e.getMessage());
        }
    }

    @Nullable
    public String findModuleApkPath(String packageName) {
        try {
            PackageManager pm = context.getPackageManager();
            android.content.pm.PackageInfo pkgInfo = pm.getPackageInfo(packageName, 0);
            if (pkgInfo != null && pkgInfo.applicationInfo != null) {
                return pkgInfo.applicationInfo.sourceDir;
            }
        } catch (PackageManager.NameNotFoundException ignored) {
        } catch (Exception e) {
            logger.e("findModuleApkPath(" + packageName + "): " + e.getMessage());
        }
        return null;
    }

    // ═════════════════════════════════════════════════════════════════
    // JSON HELPERS
    // ═════════════════════════════════════════════════════════════════

    private static boolean jsonHasField(String json, String field) {
        if (json == null || field == null) return false;
        try {
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            return obj.has(field);
        } catch (Throwable t) {
            return false;
        }
    }
}