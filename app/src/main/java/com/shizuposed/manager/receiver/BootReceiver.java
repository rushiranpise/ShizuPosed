package com.shizuposed.manager.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;

import com.shizuposed.manager.ShizuPosedManagerApp;

/**
 * Boot receiver.
 *
 * What it does NOT do:
 *   It does NOT start ShizuPosedService directly. The service refuses
 *   to run without an authorized Shizuku, and at boot time Shizuku is
 *   almost never ready yet — so trying to start it would just spin up
 *   the service process, hit the authorization gate, and stopSelf().
 *
 * What it DOES do:
 *   1. Reads the auto_start preference. If disabled, does nothing.
 *   2. Touches ShizuPosedManagerApp to force the Application object to
 *      be created. That registers the Shizuku binder listeners. From
 *      that point on, the Application's own autoStartService() will
 *      fire the service the moment the grant arrives — with no retry
 *      loop, no fixed budget, and no wasted foreground-service calls.
 *   3. As a fallback, if the Application is already alive AND Shizuku
 *      is already authorized at boot (e.g. Sui or a rooted device
 *      where Shizuku starts before us), it asks the Application to
 *      auto-start now.
 */
public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "BootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action)) {
            return;
        }

        SharedPreferences prefs = context.getSharedPreferences(
            "shizuposed_settings", Context.MODE_PRIVATE);
        if (!prefs.getBoolean("auto_start", false)) {
            Log.i(TAG, "auto_start disabled — nothing to do");
            return;
        }

        Log.i(TAG, "BOOT_COMPLETED received — waking app so Shizuku listeners register");

        final Context appCtx = context.getApplicationContext();

        // Force the Application to be created. This registers the binder
        // listeners inside ShizuPosedManagerApp, so from now on the
        // auto-start will fire naturally when the Shizuku grant arrives.
        try {
            ShizuPosedManagerApp app = ShizuPosedManagerApp.getInstance();
            if (app == null) {
                // Application not created yet — touch it. This triggers
                // Application.onCreate() on the next scheduler tick, which
                // in turn calls deferredShizukuSetup() and registers the
                // listeners.
                appCtx.getApplicationContext();
                Log.i(TAG, "App instance not yet created — will initialize on next tick");
                return;
            }

            // The Application is already alive. If Shizuku happens to be
            // authorized right now (rare at boot, but possible with Sui
            // or a pre-started Shizuku), kick the auto-start.
            if (app.isShizukuAuthorized()) {
                Log.i(TAG, "Shizuku already authorized at boot — requesting auto-start");
                app.autoStartService();
            } else {
                Log.i(TAG, "Shizuku not authorized yet — Application will "
                        + "auto-start when the grant arrives");
            }
        } catch (Throwable t) {
            Log.w(TAG, "Boot wake failed: " + t.getClass().getSimpleName()
                    + ": " + t.getMessage());
        }
    }
}