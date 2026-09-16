package com.shizuposed.manager.core;

import android.util.Log;

import java.lang.reflect.Field;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.XC_MethodHook;

/**
 * AmiruDispatcher
 *
 * The Java-side counterpart to amiru_dispatch() in libamiru.c.
 *
 * For every hooked method, the native stub allocates a slot and
 * calls dispatch(slot, thisObj, args) on this class. The dispatcher
 * looks up the registered XC_MethodHook for that slot, runs its
 * beforeHookedMethod callback, and returns a long telling the stub
 * what to do next:
 *
 *   0 — proceed to the original method (no replacement was set)
 *   1 — a replacement was set; the stub returns immediately
 *
 * Field access on MethodHookParam goes through reflection because
 * the shim's fields are not public and this class lives in a
 * different package from the shim.
 */
public final class AmiruDispatcher {

    private static final String TAG = "ShizuPosedAmiru";

    /** Per-slot registration. Key is the slot index returned by
     *  NativeBridge.amiruHookMethod(). */
    private static final ConcurrentHashMap<Integer, Slot> sSlots =
        new ConcurrentHashMap<>();

    /** Cached reflection handles for MethodHookParam fields. */
    private static volatile Field fReturnEarly;
    private static volatile Field fMethod;
    private static volatile Field fThisObject;
    private static volatile Field fArgs;
    private static volatile Field fResult;
    private static volatile boolean reflectionReady = false;

    private static final class Slot {
        final XC_MethodHook hook;
        final XC_MethodHook.MethodHookParam param;
        Slot(XC_MethodHook hook, XC_MethodHook.MethodHookParam param) {
            this.hook = hook;
            this.param = param;
        }
    }

    private AmiruDispatcher() {}

    // ═════════════════════════════════════════════════════════════
    // REFLECTION SETUP
    // ═════════════════════════════════════════════════════════════

    private static synchronized void ensureReflection() {
        if (reflectionReady) return;
        reflectionReady = true;
        Class<?> c = XC_MethodHook.MethodHookParam.class;
        fReturnEarly = findField(c, "returnEarly");
        fMethod      = findField(c, "method");
        fThisObject  = findField(c, "thisObject");
        fArgs        = findField(c, "args");
        fResult      = findField(c, "result");
    }

    private static Field findField(Class<?> c, String name) {
        try {
            Field f = c.getDeclaredField(name);
            f.setAccessible(true);
            return f;
        } catch (Throwable t) {
            try {
                Field f = c.getField(name);
                f.setAccessible(true);
                return f;
            } catch (Throwable t2) {
                Log.w(TAG, "field not found: " + name);
                return null;
            }
        }
    }

    private static void setField(Field f, Object target, Object value) {
        if (f == null) return;
        try { f.set(target, value); } catch (Throwable ignored) {}
    }

    private static Object getField(Field f, Object target) {
        if (f == null) return null;
        try { return f.get(target); } catch (Throwable ignored) { return null; }
    }

    // ═════════════════════════════════════════════════════════════
    // REGISTRATION
    // ═════════════════════════════════════════════════════════════

    /**
     * Bind a slot to a hook callback. Called by AmiruBackend after
     * NativeBridge.amiruHookMethod() returns a valid slot.
     */
    public static void register(int slot, XC_MethodHook hook) {
        if (hook == null) return;
        ensureReflection();
        XC_MethodHook.MethodHookParam param = new XC_MethodHook.MethodHookParam();
        sSlots.put(slot, new Slot(hook, param));
    }

    /** Clear a slot when a hook is uninstalled. */
    public static void unregister(int slot) {
        sSlots.remove(slot);
    }

    /** Number of currently registered slots. Diagnostic only. */
    public static int registeredSlotCount() {
        return sSlots.size();
    }

    /** Clear all registrations. Called on backend teardown. */
    public static void clear() {
        sSlots.clear();
    }

    // ═════════════════════════════════════════════════════════════
    // NATIVE ENTRY POINT
    // ═════════════════════════════════════════════════════════════

    /**
     * Called from libamiru.c:amiru_dispatch() on every hooked call.
     *
     * Signature must match the JNI lookup in JNI_OnLoad:
     *   "(ILjava/lang/Object;[Ljava/lang/Object;)J"
     */
    public static long dispatch(int slot, Object thisObj, Object[] args) {
        Slot s = sSlots.get(slot);
        if (s == null) return 0;

        XC_MethodHook.MethodHookParam param = s.param;

        // Reset per-call state. Fields are set via reflection because
        // the shim's MethodHookParam does not expose them publicly.
        setField(fMethod, param, null);
        setField(fThisObject, param, thisObj);
        setField(fArgs, param, args != null ? args : new Object[0]);
        setField(fResult, param, null);
        setField(fReturnEarly, param, Boolean.FALSE);

        try {
            // beforeHookedMethod is protected in XC_MethodHook. Since
            // we're in a different package, we cannot call it directly.
            // Use the public wrapper that XC_MethodHook exposes, or
            // reflect into the protected method.
            invokeBefore(s.hook, param);
        } catch (Throwable t) {
            Log.e(TAG, "beforeHookedMethod threw for slot " + slot, t);
            return 0;
        }

        Object early = getField(fReturnEarly, param);
        if (Boolean.TRUE.equals(early)) {
            return 1;
        }
        return 0;
    }

    /**
     * Invoke XC_MethodHook.beforeHookedMethod via reflection.
     *
     * The shim's XC_MethodHook is normally invoked by XposedBridge
     * via an internal helper. In the Amiru path, no such helper
     * exists, so we reflect into the protected method directly.
     * Subclasses of XC_MethodHook override it; the JVM resolves to
     * the override at invoke time.
     */
    private static void invokeBefore(XC_MethodHook hook,
                                     XC_MethodHook.MethodHookParam param)
            throws Throwable {
        java.lang.reflect.Method before = findBeforeMethod(hook.getClass());
        if (before == null) {
            // No override — the default is a no-op. Nothing to do.
            return;
        }
        before.setAccessible(true);
        before.invoke(hook, param);
    }

    private static java.lang.reflect.Method findBeforeMethod(Class<?> c) {
        Class<?> cur = c;
        while (cur != null && cur != XC_MethodHook.class) {
            try {
                java.lang.reflect.Method m =
                    cur.getDeclaredMethod("beforeHookedMethod",
                        XC_MethodHook.MethodHookParam.class);
                return m;
            } catch (NoSuchMethodException e) {
                cur = cur.getSuperclass();
            }
        }
        // Fall back to the base class method.
        try {
            java.lang.reflect.Method m =
                XC_MethodHook.class.getDeclaredMethod("beforeHookedMethod",
                    XC_MethodHook.MethodHookParam.class);
            return m;
        } catch (NoSuchMethodException e) {
            return null;
        }
    }
}