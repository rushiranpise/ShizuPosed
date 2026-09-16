package com.shizuposed.manager.core.backends;

import android.util.Log;

import com.shizuposed.manager.core.AmiruDispatcher;
import com.shizuposed.manager.core.HookDispatcher;
import com.shizuposed.manager.core.NativeBridge;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import de.robv.android.xposed.XC_MethodHook;

/**
 * AmiruBackend
 *
 * Dispatcher-level backend that routes a hook request through the
 * Amiru native engine (libamiru.so). Amiru is a per-method stub
 * generator: for each hooked method it emits a small ARM64 stub
 * that saves the incoming registers, calls a shared C dispatcher,
 * and either tail-branches to the original entry point or returns
 * a replacement value.
 *
 * PLACEMENT
 * ---------
 * Amiru sits between the two Pine backends and NativeBackend in the
 * dispatcher chain. On a device where Pine works, Amiru never runs.
 * On a device where Pine's layout assumptions fail, Amiru provides
 * a second dynamic engine. On a device where Amiru also fails, the
 * existing NativeBackend takes over.
 *
 * CAPABILITIES (v0.1)
 * -------------------
 *   ✓ Static methods with primitive parameters
 *   ✓ Primitive return-value replacement
 *   ✓ Before-hooks
 *   ✗ Object arguments (arrive as null)
 *   ✗ thisObject for instance methods (arrives as null)
 *   ✗ After-hooks
 *   ✗ JIT invalidation
 *
 * The backend declines any method it cannot handle, so the
 * dispatcher falls through to the next backend.
 */
public final class AmiruBackend implements HookDispatcher.Backend {

    private static final String TAG = "ShizuPosedAmiru";

    // ─── Instance-level state (required by the Backend interface) ───

    /** Per-instance availability cache, populated on first isAvailable(). */
    private volatile Boolean availableCache = null;

    // ─── Class-level state (the native side is process-global) ──────

    private static volatile boolean sProbed = false;
    private static volatile boolean sAvailable = false;
    private static volatile String sStatus = "Amiru: not probed";

    public AmiruBackend() {}

    // ═════════════════════════════════════════════════════════════
    // BACKEND INTERFACE
    // ═════════════════════════════════════════════════════════════

    @Override
    public String name() {
        return "Amiru";
    }

    @Override
    public boolean isAvailable() {
        Boolean cached = availableCache;
        if (cached != null) return cached;
        boolean ok = probeOnce();
        availableCache = ok;
        return ok;
    }

    @Override
    public boolean hook(Method original, XC_MethodHook callback) throws Throwable {
        return tryHookStatic(original, callback);
    }

    @Override
    public boolean hookConstructor(Constructor<?> original,
                                   XC_MethodHook callback) throws Throwable {
        // Constructors are not supported in v0.1.
        return false;
    }

    @Override
    public void shutdown() {
        try {
            AmiruDispatcher.clear();
        } catch (Throwable t) {
            Log.w(TAG, "shutdown: " + t.getMessage());
        }
    }

    // ═════════════════════════════════════════════════════════════
    // AVAILABILITY — class-level probe
    // ═════════════════════════════════════════════════════════════

    /**
     * Static availability query. Runs the probe once and caches the
     * result for the process lifetime.
     */
    public static boolean isAmiruAvailable() {
        return probeOnce();
    }

    /** Human-readable status for the manager UI and logs. */
    public static String describe() {
        probeOnce();
        return sStatus;
    }

    private static synchronized boolean probeOnce() {
        if (sProbed) return sAvailable;
        sProbed = true;

        try {
            boolean loaded = NativeBridge.amiruLoadLibrary();
            if (!loaded) {
                sAvailable = false;
                sStatus = "Amiru: library unavailable";
                return false;
            }

            boolean ok = NativeBridge.amiruProbeLayout();
            if (!ok) {
                sAvailable = false;
                sStatus = "Amiru: layout probe failed";
                return false;
            }

            sAvailable = true;
            sStatus = "Amiru: " + NativeBridge.amiruDescribeLayout();
            Log.i(TAG, "probe: " + sStatus);
            return true;

        } catch (Throwable t) {
            sAvailable = false;
            sStatus = "Amiru: probe threw " + t.getClass().getSimpleName();
            Log.e(TAG, "probe failed", t);
            return false;
        }
    }

    // ═════════════════════════════════════════════════════════════
    // HOOK INSTALL
    // ═════════════════════════════════════════════════════════════

    private static boolean tryHookStatic(Method method, XC_MethodHook callback) {
        if (!probeOnce()) return false;
        if (method == null || callback == null) return false;

        Class<?>[] params = method.getParameterTypes();
        if (!areParamsPrimitiveOnly(params)) {
            Log.d(TAG, "skipping " + method + " — non-primitive parameter");
            return false;
        }

        // Instance methods are not supported in v0.1 because
        // thisObject arrives as null in the callback.
        if (!Modifier.isStatic(method.getModifiers())) {
            Log.d(TAG, "skipping instance method " + method
                + " — thisObject not yet supported");
            return false;
        }

        try {
            long artMethod = NativeBridge.amiruGetArtMethod(method);
            if (artMethod == 0) {
                Log.w(TAG, "no ArtMethod for " + method);
                return false;
            }

            String shorty = buildShorty(params);
            boolean isStatic = Modifier.isStatic(method.getModifiers());

            int slot = NativeBridge.amiruHookMethod(
                artMethod,
                params.length,
                shorty,
                isStatic,
                callback);

            if (slot < 0) {
                Log.w(TAG, "native hook install rejected for " + method);
                return false;
            }

            AmiruDispatcher.register(slot, callback);
            Log.i(TAG, "installed hook on " + method + " (slot " + slot
                + ", shorty " + shorty + ")");
            return true;

        } catch (Throwable t) {
            Log.e(TAG, "hook failed for " + method, t);
            return false;
        }
    }

    // ═════════════════════════════════════════════════════════════
    // HELPERS
    // ═════════════════════════════════════════════════════════════

    private static boolean areParamsPrimitiveOnly(Class<?>[] params) {
        for (Class<?> p : params) {
            if (p == null) return false;
            if (p.isPrimitive()) continue;
            return false;
        }
        return true;
    }

    private static String buildShorty(Class<?>[] params) {
        StringBuilder sb = new StringBuilder(params.length);
        for (Class<?> p : params) {
            if (p == boolean.class)   sb.append('Z');
            else if (p == byte.class) sb.append('B');
            else if (p == char.class) sb.append('C');
            else if (p == short.class) sb.append('S');
            else if (p == int.class)  sb.append('I');
            else if (p == long.class) sb.append('J');
            else if (p == float.class) sb.append('F');
            else if (p == double.class) sb.append('D');
            else                      sb.append('L');
        }
        return sb.toString();
    }

    // ═════════════════════════════════════════════════════════════
    // DIAGNOSTICS
    // ═════════════════════════════════════════════════════════════

    public static String summary() {
        return "AmiruBackend{available=" + probeOnce()
            + ", registered=" + AmiruDispatcher.registeredSlotCount()
            + ", status=" + sStatus + "}";
    }
}