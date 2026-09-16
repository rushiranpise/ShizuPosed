package com.shizuposed.manager;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.shizuposed.manager.adapter.MainPagerAdapter;
import com.shizuposed.manager.service.ShizuPosedService;
import com.shizuposed.manager.ui.HomeFragment;
import com.shizuposed.manager.ui.LogsFragment;
import com.shizuposed.manager.ui.ModulesFragment;
import com.shizuposed.manager.ui.RepoFragment;
import com.shizuposed.manager.utils.Logger;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {
    private static final String VERSION_LABEL = "3.9";

    private ViewPager2 viewPager;
    private BottomNavigationView bottomNavigation;
    private Toolbar toolbar;
    private MainPagerAdapter pagerAdapter;
    private ShizuPosedManagerApp app;
    private Logger logger;
    private boolean permissionsGranted = false;
    private boolean shizukuChecked = false;

    private boolean notActiveDialogShownThisSession = false;
    private boolean lastServiceRunning = false;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private final ActivityResultLauncher<String[]> requestPermissionsLauncher =
        registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(),
            result -> {
                boolean allGranted = true;
                for (Boolean granted : result.values()) {
                    if (!granted) { allGranted = false; break; }
                }
                permissionsGranted = allGranted;

                if (allGranted) {
                    logger.i("✅ All runtime permissions granted");
                } else {
                    logger.d("Optional runtime permissions not granted: " + result);
                }

                shizukuChecked = false;
                // Refresh Shizuku status whether or not permissions were
                // granted. The two concerns are independent; a denied
                // notification permission must not block the toolbar
                // from reflecting a successful Shizuku grant.
                refreshShizukuStatus();
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        applySystemBarInsets();

        app = ShizuPosedManagerApp.getInstance();
        app.setMainActivity(this);
        logger = Logger.getInstance(this);

        initViews();
        setupToolbar();
        setupViewPager();
        setupBottomNavigation();

        startServices();
        checkAndRequestPermissions();
    }

    private void applySystemBarInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.activityRoot),
                (view, insets) -> {
                    Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
                    view.setPadding(
                            systemBars.left,
                            systemBars.top,
                            systemBars.right,
                            systemBars.bottom);
                    return insets;
                });
        ViewCompat.requestApplyInsets(findViewById(R.id.activityRoot));
    }

    // ═════════════════════════════════════════════════════════════
    // PERMISSIONS + INIT
    // ═════════════════════════════════════════════════════════════

    /**
     * Request runtime permissions if needed, then refresh Shizuku
     * status. The two concerns are independent:
     *
     *   - Runtime permissions (POST_NOTIFICATIONS on API 33+) affect
     *     whether the foreground service can post notifications.
     *   - Shizuku permission affects whether privileged operations
     *     can run at all.
     */
    private void checkAndRequestPermissions() {
        List<String> permissionsNeeded = new ArrayList<>();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(Manifest.permission.POST_NOTIFICATIONS);
            }
        }

        if (!permissionsNeeded.isEmpty()) {
            logger.i("Requesting " + permissionsNeeded.size() + " permissions...");
            requestPermissionsLauncher.launch(permissionsNeeded.toArray(new String[0]));
        } else {
            permissionsGranted = true;
            logger.i("✅ All permissions already granted");
        }

        // Always schedule a Shizuku status refresh.
        mainHandler.postDelayed(() -> {
            if (isFinishing() || isDestroyed()) return;
            refreshShizukuStatus();
        }, 300);
    }

    /**
     * Called by the Application when ShizukuHelper reports the grant.
     */
    public void onShizukuPermissionGranted() {
        runOnUiThread(() -> {
            shizukuChecked = true;
            notActiveDialogShownThisSession = false;
            updateToolbarFromHelper();
            refreshAll();
            invalidateOptionsMenu();
        });
    }

    public void onServiceAutoStarted() {
        runOnUiThread(() -> {
            refreshAll();
            updateServiceMenuState();
        });
    }

    // ═════════════════════════════════════════════════════════════
    // SHIZUKU STATE REFLECTION
    // ═════════════════════════════════════════════════════════════

    private void updateToolbarFromHelper() {
        try {
            ShizukuHelper helper = ShizukuHelper.getInstance(this);
            String status = helper.getStatusString();
            updateToolbarStatus(VERSION_LABEL + " • " + status);
        } catch (Throwable t) {
            updateToolbarStatus(VERSION_LABEL);
        }
    }

    private void refreshShizukuStatus() {
        try {
            ShizukuHelper helper = ShizukuHelper.getInstance(this);
            boolean available = helper.isAvailable();
            boolean authorized = helper.isAuthorized();
            shizukuChecked = true;

            String provider = helper.isSui() ? "Sui"
                            : helper.isSheveryOnly() ? "Shevery(stub)"
                            : "Shizuku";

            logger.i("Shizuku status — available=" + available
                    + " authorized=" + authorized
                    + " provider=" + provider);

            if (!available) {
                ShizukuHelper.ShizukuStatus status = helper.checkShizukuActive();
                if (status == ShizukuHelper.ShizukuStatus.NOT_INSTALLED) {
                    showShizukuNotInstalledDialog();
                } else if (status == ShizukuHelper.ShizukuStatus.NOT_ACTIVE) {
                    if (!notActiveDialogShownThisSession) {
                        notActiveDialogShownThisSession = true;
                        showShizukuNotActiveDialog();
                    }
                }
            } else {
                notActiveDialogShownThisSession = false;
            }

            updateToolbarFromHelper();

        } catch (Exception e) {
            logger.e("Shizuku check error: " + e.getMessage());
            updateToolbarStatus(VERSION_LABEL + " • ⏹ Shizuku Stopped");
        }
    }

    // ═════════════════════════════════════════════════════════════
    // SHIZUKU DIALOGS
    // ═════════════════════════════════════════════════════════════

    private void showShizukuNotInstalledDialog() {
        if (isFinishing() || isDestroyed()) return;
        new AlertDialog.Builder(this)
            .setTitle("Shizuku Not Found")
            .setMessage("Shizuku is required for privileged operations.\n\n" +
                       "Please install Shizuku from:\n" +
                       "• Google Play Store\n" +
                       "• F-Droid\n" +
                       "• GitHub: https://shizuku.rikka.app/")
            .setPositiveButton("Open Play Store", (d, w) -> {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW,
                        Uri.parse("market://details?id=moe.shizuku.manager")));
                } catch (Exception e) {
                    startActivity(new Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://shizuku.rikka.app/")));
                }
            })
            .setNegativeButton("Skip", (d, w) ->
                updateToolbarStatus(VERSION_LABEL + " • ⚠️ Limited"))
            .setCancelable(false)
            .show();
    }

    private void showShizukuNotActiveDialog() {
        if (isFinishing() || isDestroyed()) return;
        new AlertDialog.Builder(this)
            .setTitle("Shizuku Not Running")
            .setMessage("Shizuku is installed but not running.\n\n" +
                       "Please start Shizuku:\n" +
                       "1. Open Shizuku app\n" +
                       "2. Tap 'Start' (requires ADB or root)\n" +
                       "3. Return to this app")
            .setPositiveButton("Open Shizuku", (d, w) -> {
                try {
                    Intent intent = getPackageManager()
                        .getLaunchIntentForPackage("moe.shizuku.manager");
                    if (intent != null) startActivity(intent);
                } catch (Exception e) {
                    Toast.makeText(this, "Please open Shizuku manually",
                            Toast.LENGTH_SHORT).show();
                }
            })
            .setNegativeButton("Skip", (d, w) ->
                updateToolbarStatus(VERSION_LABEL + " • ⚠️ Limited"))
            .setCancelable(false)
            .show();
    }

    private void updateToolbarStatus(String status) {
        runOnUiThread(() -> {
            if (toolbar != null) toolbar.setSubtitle(status);
        });
    }

    // ═════════════════════════════════════════════════════════════
    // LIFECYCLE
    // ═════════════════════════════════════════════════════════════

    @Override
    protected void onResume() {
        super.onResume();

        try {
            ShizukuHelper.getInstance(this).refreshFromBinder();
        } catch (Throwable ignored) {}

        shizukuChecked = false;
        mainHandler.postDelayed(() -> {
            if (isFinishing() || isDestroyed()) return;
            refreshShizukuStatus();
        }, 200);

        updateServiceMenuState();
    }

    // ═════════════════════════════════════════════════════════════
    // VIEW SETUP
    // ═════════════════════════════════════════════════════════════

    private void initViews() {
        viewPager = findViewById(R.id.viewPager);
        bottomNavigation = findViewById(R.id.bottomNavigation);
        toolbar = findViewById(R.id.toolbar);
    }

    private void setupToolbar() {
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle("ShizuPosed Manager");
        }
    }

    private void setupViewPager() {
        pagerAdapter = new MainPagerAdapter(this);
        viewPager.setAdapter(pagerAdapter);
        viewPager.setOffscreenPageLimit(4);
        viewPager.setUserInputEnabled(false);
    }

    private void setupBottomNavigation() {
        bottomNavigation.setOnItemSelectedListener(item -> {
            int itemId = item.getItemId();
            if (itemId == R.id.nav_home)     { viewPager.setCurrentItem(0); return true; }
            if (itemId == R.id.nav_modules)  { viewPager.setCurrentItem(1); return true; }
            if (itemId == R.id.nav_repo)     { viewPager.setCurrentItem(2); return true; }
            if (itemId == R.id.nav_logs)     { viewPager.setCurrentItem(3); return true; }
            if (itemId == R.id.nav_settings) { viewPager.setCurrentItem(4); return true; }
            return false;
        });
    }

    // ═════════════════════════════════════════════════════════════
    // SERVICE LIFECYCLE
    // ═════════════════════════════════════════════════════════════

    private void startServices() {
        try {
            // ── Authorization gate ─────────────────────────────
            // Do not start the service unless Shizuku is authorized.
            // The Application's autoStartService() will fire later, when
            // the grant arrives — the user doesn't need to do anything.
            ShizukuHelper helper = ShizukuHelper.getInstance(this);
            boolean authorized = helper.isAvailable() && helper.isAuthorized();
            if (!authorized) {
                logger.i("startServices: Shizuku not authorized — deferring "
                        + "service start until grant arrives");
                return;
            }

            if (app != null && app.isServiceAutoStarted()) {
                logger.d("Service already auto-started by Application — skipping");
                return;
            }

            Intent serviceIntent = new Intent(this, ShizuPosedService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent);
            } else {
                startService(serviceIntent);
            }
            logger.i("Service start dispatched from MainActivity");
        } catch (Throwable t) {
            logger.e("Failed to start service: " + t.getMessage());
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main_options_menu, menu);
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        boolean running = isShizuPosedServiceRunning();

        boolean authorized = false;
        try {
            ShizukuHelper helper = ShizukuHelper.getInstance(this);
            authorized = helper.isAvailable() && helper.isAuthorized();
        } catch (Throwable ignored) {}

        MenuItem startItem = menu.findItem(R.id.action_start_service);
        MenuItem stopItem  = menu.findItem(R.id.action_stop_service);

        // Only offer "Start" when authorized AND not already running.
        if (startItem != null) startItem.setVisible(authorized && !running);
        if (stopItem != null)  stopItem.setVisible(running);

        lastServiceRunning = running;
        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();

        if (id == R.id.action_start_service) {
            handleStartService();
            return true;
        } else if (id == R.id.action_stop_service) {
            handleStopService();
            return true;
        } else if (id == R.id.action_status) {
            showStatusDialog();
            return true;
        } else if (id == R.id.action_refresh) {
            refreshAll();
            return true;
        } else if (id == R.id.action_restart) {
            restartServices();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void handleStartService() {
        try {
            ShizukuHelper helper = ShizukuHelper.getInstance(this);
            if (!helper.isAvailable() || !helper.isAuthorized()) {
                Toast.makeText(this,
                    "Cannot start: Shizuku is not authorized.\n" +
                    "Open Shizuku and grant permission first.",
                    Toast.LENGTH_LONG).show();
                logger.w("handleStartService blocked: Shizuku not authorized");
                return;
            }

            if (app != null && app.isServiceAutoStarted()) {
                Toast.makeText(this, "Service already running",
                        Toast.LENGTH_SHORT).show();
                updateServiceMenuState();
                return;
            }

            Intent serviceIntent = new Intent(this, ShizuPosedService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent);
            } else {
                startService(serviceIntent);
            }
            Toast.makeText(this, "Service starting…", Toast.LENGTH_SHORT).show();
            logger.i("Service start requested from menu");

            mainHandler.postDelayed(() -> {
                if (isFinishing() || isDestroyed()) return;
                updateServiceMenuState();
                refreshAll();
            }, 500);

        } catch (Throwable t) {
            Toast.makeText(this, "Failed to start service",
                    Toast.LENGTH_SHORT).show();
            logger.e("handleStartService error: " + t.getMessage());
        }
    }

    private void handleStopService() {
        new AlertDialog.Builder(this)
            .setTitle("Stop Service")
            .setMessage("Stop the ShizuPosed service?\n\n" +
                       "Running hooks will continue in already-launched targets, " +
                       "but new targets will not be hooked until the service " +
                       "is started again.")
            .setPositiveButton("Stop", (d, w) -> {
                try {
                    Intent serviceIntent = new Intent(this, ShizuPosedService.class);
                    stopService(serviceIntent);
                    Toast.makeText(this, "Service stopped",
                            Toast.LENGTH_SHORT).show();
                    logger.i("Service stop requested from menu");

                    lastServiceRunning = false;
                    invalidateOptionsMenu();
                    mainHandler.postDelayed(() -> {
                        if (isFinishing() || isDestroyed()) return;
                        updateServiceMenuState();
                        refreshAll();
                    }, 500);

                } catch (Throwable t) {
                    Toast.makeText(this, "Failed to stop service",
                            Toast.LENGTH_SHORT).show();
                    logger.e("handleStopService error: " + t.getMessage());
                }
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private boolean isShizuPosedServiceRunning() {
        try {
            if (ShizuPosedService.isServiceRunning()) return true;
        } catch (Throwable ignored) {}
        try {
            if (app != null && app.isServiceAutoStarted()) return true;
        } catch (Throwable ignored) {}
        return false;
    }

    private void updateServiceMenuState() {
        boolean running = isShizuPosedServiceRunning();
        if (running != lastServiceRunning) {
            lastServiceRunning = running;
            invalidateOptionsMenu();
        }
    }

    private void refreshAll() {
        if (pagerAdapter == null) return;

        for (int i = 0; i < pagerAdapter.getItemCount(); i++) {
            Fragment fragment = pagerAdapter.getFragment(i);
            if (fragment == null) continue;
            if (!fragment.isAdded()) continue;

            try {
                if (fragment instanceof HomeFragment) {
                    ((HomeFragment) fragment).refresh();
                } else if (fragment instanceof ModulesFragment) {
                    ((ModulesFragment) fragment).refresh();
                } else if (fragment instanceof RepoFragment) {
                    ((RepoFragment) fragment).refresh();
                } else if (fragment instanceof LogsFragment) {
                    ((LogsFragment) fragment).refresh();
                }
            } catch (Throwable t) {
                if (logger != null) {
                    logger.e("refreshAll: " + fragment.getClass().getSimpleName()
                            + " threw: " + t.getMessage());
                }
            }
        }

        shizukuChecked = false;
        refreshShizukuStatus();
    }

    private void restartServices() {
        try {
            ShizukuHelper helper = ShizukuHelper.getInstance(this);
            if (!helper.isAvailable() || !helper.isAuthorized()) {
                Toast.makeText(this,
                    "Cannot restart: Shizuku is not authorized.",
                    Toast.LENGTH_LONG).show();
                logger.w("restartServices blocked: Shizuku not authorized");
                return;
            }

            Intent serviceIntent = new Intent(this, ShizuPosedService.class);
            stopService(serviceIntent);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent);
            } else {
                startService(serviceIntent);
            }
            Toast.makeText(this, "Service restarted", Toast.LENGTH_SHORT).show();

            mainHandler.postDelayed(() -> {
                if (isFinishing() || isDestroyed()) return;
                updateServiceMenuState();
                refreshAll();
            }, 500);
        } catch (Throwable t) {
            Toast.makeText(this, "Failed to restart service",
                    Toast.LENGTH_SHORT).show();
            logger.e("Restart service error: " + t.getMessage());
        }
    }

    private void showStatusDialog() {
        ShizukuHelper helper = ShizukuHelper.getInstance(this);
        boolean available = helper.isAvailable();
        boolean authorized = helper.isAuthorized();
        int version = helper.getVersion();
        boolean isSui = helper.isSui();
        boolean sheveryOnly = helper.isSheveryOnly();

        boolean serviceRunning = isShizuPosedServiceRunning();

        StringBuilder status = new StringBuilder();
        status.append("ShizuPosed ").append(VERSION_LABEL).append("\n\n");
        status.append("Permissions: ")
              .append(permissionsGranted ? "✅ Granted" : "⚠️ Missing").append("\n");
        status.append("Shizuku Status: ")
              .append(available ? "✅ Available" : "❌ Unavailable").append("\n");
        status.append("Authorization: ")
              .append(authorized ? "✅ Authorized" : "❌ Not Authorized").append("\n");
        status.append("Shizuku Version: ").append(version).append("\n");
        if (isSui) {
            status.append("Sui Active: ✅ Yes\n");
        }
        if (sheveryOnly) {
            status.append("Provider: Shevery (compat layer)\n");
        }
        status.append("\n");
        status.append("Service: ")
              .append(serviceRunning ? "✅ Running" : "❌ Stopped").append("\n");
        status.append("\n");
        status.append("Privileged Mode: ")
              .append(authorized ? "🔑 Enabled" : "⚠️ Limited");

        new AlertDialog.Builder(this)
            .setTitle("Status")
            .setMessage(status.toString())
            .setPositiveButton("OK", null)
            .show();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
    }
}