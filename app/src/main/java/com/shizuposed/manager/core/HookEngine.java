package com.shizuposed.manager.core;

import com.shizuposed.manager.utils.Logger;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.XC_MethodHook;

/**
 * HookEngine
 *
 * Front door to the hook subsystem. Registers all available backends
 * with HookDispatcher, chooses the primary one, and exposes the same
 * public API as before.
 *
 * Backends (in priority order):
 *   1. Pine (AUTO mode)         — fastest, works on most methods
 *   2. Pine (REPLACEMENT mode)  — slower, catches methods AUTO can't hook
 *   3. Proxy                    — interface methods only, pure Java
 *   4. Noop                     — always succeeds, does nothing
 *
 * The dispatcher tries each in order per hook, so one backend failing
 * on a specific method doesn't take down the whole framework.
 */
public class HookEngine {

    private static final String TAG = "HookEngine";
    private static HookEngine instance;

    private final ConcurrentHashMap<String, Method> registeredHooks = new ConcurrentHashMap<>();
    private volatile boolean initialized = false;
    private volatile boolean backendsInstalled = false;

    private HookEngine() {}

    public static synchronized HookEngine getInstance() {
        if (instance == null) instance = new HookEngine();
        return instance;
    }

    public synchronized void init() {
        if (initialized) return;
        initialized = true;

        installBackends();

        HookDispatcher d = HookDispatcher.getInstance();
        log("HookEngine initialized (primary="
            + (d.getPrimary() != null ? d.getPrimary().name() : "none") + ")");
    }

    /**
     * Idempotent. Safe to call before every hook operation.
     */
    public static void ensureBackendInstalled() {
        getInstance().installBackends();
    }

    // ═════════════════════════════════════════════════════════════════
    // BACKEND REGISTRATION
    // ═════════════════════════════════════════════════════════════════

    private synchronized void installBackends() {
        if (backendsInstalled) return;
        backendsInstalled = true;

        HookDispatcher d = HookDispatcher.getInstance();

        // 1. Pine AUTO mode — fastest when it works
        d.register(new com.shizuposed.manager.core.backends.PineBackend());

        // 2. Pine REPLACEMENT mode — catches methods Pine AUTO rejects
        d.register(new com.shizuposed.manager.core.backends.PineReplaceBackend());

        d.register(new com.shizuposed.manager.core.backends.AmiruBackend());
 
        d.register(new com.shizuposed.manager.core.backends.NativeBackend());

        // 3. Instrumentation — handles Application / Activity lifecycle
        //    hooks when Pine can't install them
        d.register(new com.shizuposed.manager.core.backends.InstrumentationBackend());

        // 4. Proxy — interface methods only
        d.register(new com.shizuposed.manager.core.backends.ProxyBackend());

        // 5. Noop — always succeeds, never fails the caller
        d.register(new com.shizuposed.manager.core.backends.NoopBackend());

        d.initialize();

        // Point XposedHookBridge at the dispatcher. This is the seam
        // every module's findAndHookMethod funnels through.
        XposedHookBridge.setBackend(new XposedHookBridge.HookBackend() {

            @Override
            public void hook(Method original, XC_MethodHook callback) {
                if (original == null || callback == null) return;

                // Try the dispatcher chain
                boolean ok = d.installHook(original, callback);

                if (ok) {
                    String key = original.getDeclaringClass().getName()
                        + "." + original.getName();
                    registeredHooks.put(key, original);
                }
                // If !ok, the Noop backend would have "succeeded" anyway,
                // so this branch is effectively unreachable in practice —
                // but we keep the check for clarity.
            }

            @Override
            public void hookConstructor(Constructor<?> original, XC_MethodHook callback) {
                if (original == null || callback == null) return;

                boolean ok = d.installConstructorHook(original, callback);
                if (ok) {
                    String key = original.getDeclaringClass().getName()
                        + ".<init>" + original.getParameterCount();
                    log("[dispatcher] hooked constructor " + key);
                }
            }
        });

        HookDispatcher.Backend primary = d.getPrimary();
        log("Backends installed. Primary="
            + (primary != null ? primary.name() : "none"));
    }

    // ═════════════════════════════════════════════════════════════════
    // PUBLIC API (kept stable for callers)
    // ═════════════════════════════════════════════════════════════════

    public boolean hookMethod(Method originalMethod, XC_MethodHook callback) {
        if (!initialized) init();
        try {
            XposedHookBridge.installHook(originalMethod, callback);
            return true;
        } catch (Throwable t) {
            log("hookMethod failed: " + t);
            return false;
        }
    }

    /** Legacy signature — accepts the callback as Object. */
    public boolean hookMethod(Method originalMethod, Object callback) {
        if (callback instanceof XC_MethodHook) {
            return hookMethod(originalMethod, (XC_MethodHook) callback);
        }
        log("hookMethod: callback is not XC_MethodHook");
        return false;
    }

    public boolean isHooked(Method method) {
        if (method == null) return false;
        String key = method.getDeclaringClass().getName() + "." + method.getName();
        return registeredHooks.containsKey(key);
    }

    public boolean isInitialized() { return initialized; }
    public int getHookedCount() { return registeredHooks.size(); }

    /**
     * Expose the dispatcher for callers who want to inspect backends.
     */
    public HookDispatcher getDispatcher() {
        return HookDispatcher.getInstance();
    }

    private static void log(String msg) {
        try {
            Logger l = Logger.getInstance(null);
            if (l != null) { l.i("[HookEngine] " + msg); return; }
        } catch (Throwable ignored) {}
        System.out.println("[HookEngine] " + msg);
    }
}