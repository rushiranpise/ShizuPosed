package com.shizuposed.manager.core;

import com.shizuposed.manager.core.compat.CompatLog;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;

/**
 * Java side of libshizuposed.so and libamiru.so.
 *
 * Two native engines are exposed through this one bridge:
 *
 *   • libshizuposed.so — the original shared-dispatcher engine.
 *     One C entry point (szp_dispatch_entry) serves all hooks, and
 *     the Java side reports per-call results through a thread-local
 *     setDispatchResult(...) channel.
 *
 *   • libamiru.so — the Amiru per-method stub engine. Each hooked
 *     method gets its own 128-byte ARM64 stub allocated from a
 *     native pool. The stub saves the incoming registers, calls a
 *     shared C dispatcher (amiru_dispatch), and either returns a
 *     replacement value or tail-branches to the original entry.
 *
 * Both libraries are loaded once per process. If either fails to
 * load (wrong ABI, missing symbol, ROM block), every method that
 * depends on it returns null/false and the dispatcher chain falls
 * through to the next backend.
 *
 * Scope: in-process only. Native hooking cannot reach apps that
 * ShizuPosed did not launch. See NativeBackend and AmiruBackend
 * for the dispatcher integration.
 */
public final class NativeBridge {

    private static final String TAG = "NativeBridge";

    // ─── libshizuposed state ───────────────────────────────────────
    private static volatile long dispatchStub = 0L;
    private static volatile boolean dispatchStubResolved = false;
    private static volatile boolean loaded = false;
    private static volatile boolean available = false;

    // ─── libamiru state ────────────────────────────────────────────
    private static volatile boolean amiruLoaded = false;
    private static volatile boolean amiruAvailable = false;
    private static volatile boolean amiruProbed = false;
    private static volatile String  amiruLayoutDescription = "not probed";

    private NativeBridge() {}

    static {
        try {
            System.loadLibrary("shizuposed");
            loaded = true;
            CompatLog.d(TAG, "libshizuposed.so loaded");
        } catch (Throwable t) {
            CompatLog.w(TAG, "libshizuposed.so not loadable", t);
            loaded = false;
        }

        try {
            System.loadLibrary("amiru");
            amiruLoaded = true;
            CompatLog.d(TAG, "libamiru.so loaded");
        } catch (Throwable t) {
            // Amiru is optional. The framework runs without it. Any
            // device that is not ARM64 will land here.
            CompatLog.w(TAG, "libamiru.so not loadable (optional)", t);
            amiruLoaded = false;
        }
    }

    // ═════════════════════════════════════════════════════════════
    // QUERIES — libshizuposed
    // ═════════════════════════════════════════════════════════════

    public static boolean isLoaded() { return loaded; }

    public static boolean isAvailable() {
        if (!loaded) return false;
        if (available) return true;
        try {
            available = nIsAvailable();
        } catch (Throwable t) {
            CompatLog.w(TAG, "nIsAvailable threw", t);
            available = false;
        }
        return available;
    }

    public static String layoutInfo() {
        if (!loaded) return "not loaded";
        try {
            return nLayoutInfo();
        } catch (Throwable t) {
            return "error: " + t.getMessage();
        }
    }

    /**
     * Resolve the shared C dispatcher entry once. Returns 0 if the
     * symbol is not exported (should not happen on a correct build).
     */
    public static long dispatchStubAddress() {
        if (dispatchStubResolved) return dispatchStub;
        synchronized (NativeBridge.class) {
            if (dispatchStubResolved) return dispatchStub;
            dispatchStubResolved = true;
            if (!loaded) return 0L;
            try {
                long p = nDlsym("libshizuposed.so", "szp_dispatch_entry");
                if (p == 0L) {
                    p = nDlsym("libshizuposed", "szp_dispatch_entry");
                }
                dispatchStub = p;
                if (p == 0L) {
                    CompatLog.w(TAG,
                            "szp_dispatch_entry not found in libshizuposed.so",
                            null);
                } else {
                    CompatLog.d(TAG, "szp_dispatch_entry = 0x"
                            + Long.toHexString(p));
                }
            } catch (Throwable t) {
                CompatLog.w(TAG, "dispatchStubAddress threw", t);
                dispatchStub = 0L;
            }
            return dispatchStub;
        }
    }

    // ═════════════════════════════════════════════════════════════
    // RAW ART HOOK — libshizuposed
    // ═════════════════════════════════════════════════════════════

    /**
     * Patch a Java method's ArtMethod entry point to `replacement`.
     * `replacement` must be a native function pointer to an
     * extern "C" function with the target's exact signature.
     *
     * Returns the trampoline address (call this to reach the
     * original), or 0 on failure.
     */
    public static long hookArtMethod(Method method, long replacement) {
        if (!isAvailable() || method == null || replacement == 0) return 0L;
        long[] out = new long[1];
        boolean ok;
        try {
            ok = nHookArtMethod(method, replacement, out);
        } catch (Throwable t) {
            CompatLog.w(TAG, "nHookArtMethod threw", t);
            return 0L;
        }
        return ok ? out[0] : 0L;
    }

    public static boolean unhookArtMethod(Method method) {
        if (!isAvailable() || method == null) return false;
        try {
            return nUnhookArtMethod(method);
        } catch (Throwable t) {
            CompatLog.w(TAG, "nUnhookArtMethod threw", t);
            return false;
        }
    }

    // ═════════════════════════════════════════════════════════════
    // ROUTED CALLBACK API — libshizuposed
    // ═════════════════════════════════════════════════════════════

    /**
     * Register a Java XC_MethodHook with the C-side router. Returns
     * the address of a per-method C stub. Pass that stub to
     * hookArtMethod() to complete the hook.
     *
     * Returns 0 on failure (native side full, duplicate registration,
     * or native bridge unavailable).
     */
    public static long registerCallback(Method method, XC_MethodHook callback) {
        if (!isAvailable() || method == null || callback == null) return 0L;
        try {
            return nRegisterCallback(method, callback);
        } catch (Throwable t) {
            CompatLog.w(TAG, "nRegisterCallback threw", t);
            return 0L;
        }
    }

    /**
     * Remove a callback registered by registerCallback(). Also frees
     * the per-method C stub. Does not unhook the ArtMethod entry; call
     * unhookArtMethod() for that first.
     */
    public static boolean unregisterCallback(Method method) {
        if (!isAvailable() || method == null) return false;
        try {
            return nUnregisterCallback(method);
        } catch (Throwable t) {
            CompatLog.w(TAG, "nUnregisterCallback threw", t);
            return false;
        }
    }

    /**
     * Convenience: register + hook in one call. Returns the trampoline
     * address on success, or 0. On failure the registration is rolled
     * back so the C-side table is not left dirty.
     */
    public static long hookArtMethodRouted(Method method, XC_MethodHook callback) {
        if (!isAvailable() || method == null || callback == null) return 0L;
        long stub = registerCallback(method, callback);
        if (stub == 0L) return 0L;
        long trampoline = hookArtMethod(method, stub);
        if (trampoline == 0L) {
            unregisterCallback(method);
            return 0L;
        }
        return trampoline;
    }

    /**
     * Convenience: unhook + unregister in one call. Safe to call on a
     * method that was never hooked (returns false).
     */
    public static boolean unhookArtMethodRouted(Method method) {
        if (!isAvailable() || method == null) return false;
        boolean unhooked = unhookArtMethod(method);
        boolean unregistered = unregisterCallback(method);
        return unhooked || unregistered;
    }

    // ═════════════════════════════════════════════════════════════
    // DISPATCH RESULT CHANNEL — libshizuposed
    // ═════════════════════════════════════════════════════════════

    /**
     * Report the result of a hook dispatch to the C side.
     *
     * @param hasResult true if the callback supplied a replacement
     *                  return value; false to fall through to the
     *                  original method.
     * @param isWide    true if the replacement value is a 64-bit
     *                  primitive (long / double); false otherwise.
     * @param value     raw bits of the replacement value. Ignored
     *                  when hasResult is false.
     */
    public static void setDispatchResult(boolean hasResult,
                                         boolean isWide,
                                         long value) {
        if (!loaded) return;
        try {
            nSetDispatchResult(hasResult, isWide, value);
        } catch (Throwable t) {
            CompatLog.d(TAG, "nSetDispatchResult threw: " + t.getMessage());
        }
    }

    /** Convenience: tell the C side to fall through to the original. */
    public static void setDispatchNoResult() {
        setDispatchResult(false, false, 0L);
    }

    /**
     * Convenience for primitive callbacks: encode `result` per the
     * declared return type and push it. Handles boolean, byte, char,
     * short, int, long, float, double. Returns true if the value was
     * encoded and pushed; false if the type is unsupported (in which
     * case the caller should fall through).
     */
    public static boolean setDispatchPrimitiveResult(Object result,
                                                     Class<?> returnType) {
        if (returnType == null || returnType == void.class) {
            setDispatchNoResult();
            return false;
        }
        try {
            if (returnType == boolean.class) {
                setDispatchResult(true, false,
                        Boolean.TRUE.equals(result) ? 1L : 0L);
                return true;
            }
            if (returnType == byte.class || returnType == short.class
                    || returnType == int.class || returnType == char.class) {
                long v = (result instanceof Number)
                        ? ((Number) result).longValue()
                        : 0L;
                setDispatchResult(true, false, v);
                return true;
            }
            if (returnType == long.class) {
                long v = (result instanceof Number)
                        ? ((Number) result).longValue()
                        : 0L;
                setDispatchResult(true, true, v);
                return true;
            }
            if (returnType == float.class) {
                int bits = (result instanceof Number)
                        ? Float.floatToRawIntBits(((Number) result).floatValue())
                        : 0;
                setDispatchResult(true, false, bits & 0xFFFFFFFFL);
                return true;
            }
            if (returnType == double.class) {
                long bits = (result instanceof Number)
                        ? Double.doubleToRawLongBits(((Number) result).doubleValue())
                        : 0L;
                setDispatchResult(true, true, bits);
                return true;
            }
        } catch (Throwable t) {
            CompatLog.w(TAG, "setDispatchPrimitiveResult failed for "
                    + returnType.getName(), t);
        }
        setDispatchNoResult();
        return false;
    }

    // ═════════════════════════════════════════════════════════════
    // NATIVE SYMBOL HOOK — libshizuposed
    // ═════════════════════════════════════════════════════════════

    /**
     * Inline-hook a native symbol. `target` is an address returned by
     * dlsym(); `replacement` is a native function pointer. Returns the
     * trampoline address, or 0.
     */
    public static long hookNative(long target, long replacement) {
        if (!isAvailable() || target == 0 || replacement == 0) return 0L;
        long[] out = new long[1];
        boolean ok;
        try {
            ok = nHookNative(target, replacement, out);
        } catch (Throwable t) {
            CompatLog.w(TAG, "nHookNative threw", t);
            return 0L;
        }
        return ok ? out[0] : 0L;
    }

    // ═════════════════════════════════════════════════════════════
    // DLSYM — libshizuposed
    // ═════════════════════════════════════════════════════════════

    public static long dlsym(String library, String symbol) {
        if (!loaded || library == null || symbol == null) return 0L;
        try {
            return nDlsym(library, symbol);
        } catch (Throwable t) {
            CompatLog.w(TAG, "nDlsym threw for " + library + "!" + symbol, t);
            return 0L;
        }
    }

    // ═════════════════════════════════════════════════════════════
    // AMIRU — LIBRARY + PROBE
    // ═════════════════════════════════════════════════════════════

    /**
     * Whether libamiru.so was loaded successfully in this process.
     * Independent of whether the layout probe succeeded.
     */
    public static boolean isAmiruLoaded() { return amiruLoaded; }

    /**
     * Load libamiru.so on demand and return true if it is now
     * loaded. The static block already tried; this exists so that
     * AmiruBackend can retry after a delayed load path (e.g. after
     * a Shizuku grant when the app_process is freshly spawned).
     *
     * Idempotent. Safe to call from any thread.
     */
    public static synchronized boolean amiruLoadLibrary() {
        if (amiruLoaded) return true;
        try {
            System.loadLibrary("amiru");
            amiruLoaded = true;
            CompatLog.d(TAG, "libamiru.so loaded (late)");
            return true;
        } catch (Throwable t) {
            CompatLog.w(TAG, "libamiru.so late load failed", t);
            return false;
        }
    }

    /**
     * Whether Amiru's layout probe has succeeded and hook
     * installation can proceed. The result is cached after the
     * first successful probe.
     */
    public static boolean isAmiruAvailable() {
        if (!amiruLoaded) return false;
        if (amiruAvailable) return true;
        return amiruProbeLayout();
    }

    /**
     * Run the native layout probe. Returns true if a valid layout
     * was found. Safe to call multiple times; the native side
     * caches the result.
     */
    public static boolean amiruProbeLayout() {
        if (!amiruLoaded) return false;
        if (amiruAvailable) return true;
        synchronized (NativeBridge.class) {
            if (amiruAvailable) return true;
            try {
                boolean ok = nAmiruProbeLayout();
                if (ok) {
                    amiruAvailable = true;
                    amiruProbed = true;
                    try {
                        amiruLayoutDescription = nAmiruDescribeLayout();
                    } catch (Throwable t) {
                        amiruLayoutDescription = "described:error";
                    }
                    CompatLog.d(TAG, "amiru layout: " + amiruLayoutDescription);
                } else {
                    amiruProbed = true;
                    amiruLayoutDescription = "probe returned false";
                }
                return ok;
            } catch (Throwable t) {
                CompatLog.w(TAG, "amiruProbeLayout threw", t);
                amiruProbed = true;
                amiruLayoutDescription = "probe threw: " + t.getMessage();
                return false;
            }
        }
    }

    /**
     * Human-readable description of the probed layout, or a status
     * string explaining why the probe is not available. Safe to call
     * before the probe runs.
     */
    public static String amiruDescribeLayout() {
        if (!amiruLoaded) return "library not loaded";
        if (!amiruProbed) {
            amiruProbeLayout();
        }
        return amiruLayoutDescription;
    }

    // ═════════════════════════════════════════════════════════════
    // AMIRU — HOOK INSTALL / UNINSTALL
    // ═════════════════════════════════════════════════════════════

    /**
     * Install an Amiru native hook on an ArtMethod.
     *
     * @param artMethodAddr the ArtMethod address, from amiruGetArtMethod
     * @param paramCount    number of declared parameters
     * @param paramShorts   JNI shorty string, one char per parameter
     * @param isStatic      true if the method is static
     * @param callback      the XC_MethodHook to associate with the slot
     * @return slot index on success, or -1 on failure
     */
    public static int amiruHookMethod(long artMethodAddr,
                                      int paramCount,
                                      String paramShorts,
                                      boolean isStatic,
                                      XC_MethodHook callback) {
        if (!amiruLoaded || !isAmiruAvailable()) return -1;
        if (artMethodAddr == 0 || paramShorts == null) return -1;
        try {
            return nAmiruHookMethod(artMethodAddr,
                                    paramCount,
                                    paramShorts,
                                    isStatic,
                                    callback);
        } catch (Throwable t) {
            CompatLog.w(TAG, "nAmiruHookMethod threw", t);
            return -1;
        }
    }

    /**
     * Remove a previously installed Amiru hook by slot.
     * Returns 0 on success, -1 on failure.
     */
    public static int amiruUnhook(int slot) {
        if (!amiruLoaded) return -1;
        try {
            return nAmiruUnhook(slot);
        } catch (Throwable t) {
            CompatLog.w(TAG, "nAmiruUnhook threw", t);
            return -1;
        }
    }

    /**
     * Resolve the ArtMethod address of a java.lang.reflect.Method.
     *
     * Tries the public getArtMethod() accessor first (available on
     * Android 11 and later), then falls back to the private
     * artMethod field. Returns 0 if neither works.
     *
     * This is a Java-side helper; it does not touch the native side.
     * It lives here because it is shared by AmiruBackend and any
     * future native engine that needs the same lookup.
     */
    public static long amiruGetArtMethod(Method method) {
        if (method == null) return 0L;

        // Android 11+: Method.getArtMethod() returns a long.
        try {
            Method getArtMethod = Method.class.getDeclaredMethod("getArtMethod");
            getArtMethod.setAccessible(true);
            Object v = getArtMethod.invoke(method);
            if (v instanceof Long) return (Long) v;
            if (v instanceof Integer) return ((Integer) v).longValue();
        } catch (Throwable ignored) {
        }

        // Older: read the artMethod field directly. The field type
        // has been long, int, and Object across versions.
        try {
            Field f = Method.class.getDeclaredField("artMethod");
            f.setAccessible(true);
            Object v = f.get(method);
            if (v instanceof Long) return (Long) v;
            if (v instanceof Integer) return ((Integer) v).longValue();
            if (v instanceof Number) return ((Number) v).longValue();
        } catch (Throwable ignored) {
        }

        return 0L;
    }

    // ═════════════════════════════════════════════════════════════
    // AMIRU — DIAGNOSTICS
    // ═════════════════════════════════════════════════════════════

    /** One-line summary for logs and the manager UI. */
    public static String amiruSummary() {
        return "Amiru{loaded=" + amiruLoaded
            + ", available=" + amiruAvailable
            + ", layout=" + amiruLayoutDescription + "}";
    }

    // ═════════════════════════════════════════════════════════════
    // NATIVES — libshizuposed
    // ═════════════════════════════════════════════════════════════

    private static native boolean nIsAvailable();
    private static native String  nLayoutInfo();
    private static native boolean nHookArtMethod(Method m, long replacement, long[] out);
    private static native boolean nUnhookArtMethod(Method m);
    private static native boolean nHookNative(long target, long replacement, long[] out);
    private static native long    nDlsym(String library, String symbol);
    private static native long    nRegisterCallback(Method m, XC_MethodHook callback);
    private static native boolean nUnregisterCallback(Method m);
    private static native void    nSetDispatchResult(boolean hasResult,
                                                     boolean isWide,
                                                     long value);

    // ═════════════════════════════════════════════════════════════
    // NATIVES — libamiru
    // ═════════════════════════════════════════════════════════════

    private static native boolean nAmiruProbeLayout();
    private static native String  nAmiruDescribeLayout();
    private static native int     nAmiruHookMethod(long artMethodAddr,
                                                  int paramCount,
                                                  String paramShorts,
                                                  boolean isStatic,
                                                  XC_MethodHook callback);
    private static native int     nAmiruUnhook(int slot);
}