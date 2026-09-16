# ShizuPosed

**A non-root Xposed-compatible hook framework built on `app_process`.**

ShizuPosed runs Xposed-API modules in apps that are launched through it, without root, without bootloader unlock, and without modifying the system partition. It uses Shizuku to invoke `app_process` as the shell UID, bootstraps a Java runtime inside the target's process, and installs method hooks through a multi-backend dispatcher. Modules written for LSPosed and classic Xposed keep working.

Version 3.9.

## Start here

ShizuPosed is an ARM64 Android 10+ manager for launching selected apps with
Xposed-compatible modules through Shizuku. It does not require root or a
modified system partition.

For the normal user path, read [Requirements](#requirements), then follow
[Installation](#installation). Developers can jump to [Building](#building),
[Module development](#module-development), or [Architecture](#architecture).

Prebuilt debug and release APKs are published by the
[Build and Release workflow](.github/workflows/release.yml) when a `v*` tag is
created. The release APK is unsigned unless signing is configured separately.

---

## What this is — and what it is not

This section exists because it determines everything else in the document.

**ShizuPosed is not a drop-in replacement for LSPosed.** It does not aim for feature parity. It uses a fundamentally different injection primitive — `app_process` into a target launched by ShizuPosed — because that is the only reliable injection path available without root on modern Android. That choice is what makes ShizuPosed possible, and it is also what defines its ceiling.

If you need zygote-wide hooking, `system_server` interception, cross-UID hooks, or `Service.onCreate` coverage, use LSPosed. ShizuPosed exists for users who cannot or will not root, and who still want LSPosed-API modules to work inside the apps ShizuPosed launches.

The rest of this document is honest about where that boundary lies.

---

## Why `app_process`

To explain ShizuPosed's shape, it helps to explain what it cannot do and why.

Without root, the available injection primitives on Android 10+ are narrow:

**`ptrace`** is blocked by SELinux for non-root UIDs. **`LD_PRELOAD`** requires a writable, executable path the target will load from; app data dirs are `noexec`. **Zygote fork** requires being inside zygote, which means root or being the ROM. **`Runtime.exec`** runs as the caller's UID, not the target's. **`am instrument`** gives you an `Instrumentation` handle after `Application.onCreate`, which is too late for bootstrap hooks. **ContentProvider hijack** only works if the target already declares a provider you can take over.

`app_process` works because it is a platform binary that already exists on every Android device, Shizuku can invoke it as shell (UID 2000), it starts a Java runtime so you can load arbitrary dex into it, and Shizuku can pass the target package and UID as arguments.

That is the entire mechanism. Everything in ShizuPosed follows from it.

---

## The timing model

LSPosed is a **zygote-timing** framework: it forks from zygote and installs hooks before the target's `Application` object exists. ShizuPosed is a **bootstrap-timing** framework: it is `app_process`-launched into the target, installs hooks, and *then* drives the target's own `ActivityThread` bootstrap so that `Application.onCreate` runs with hooks already live.

What ShizuPosed reaches in bootstrap mode:

- `Application.attachBaseContext` — hookable
- `Application.onCreate` — hookable
- `ContentProvider.onCreate` — hookable
- `Activity.onCreate` — hookable via Pine, Amiru, Native, or Instrumentation
- `Service.onCreate` — not hookable
- `system_server` — not hookable

The `Service.onCreate` gap is structural. `Instrumentation` does not dispatch Service lifecycle, the native engines can only install hooks from bootstrap, and `app_process` cannot get into a service process it did not launch. There is no fallback because there is no mechanism.

---

## Architecture

```
ShizuPosed Manager (APK)
        │
        │  Shizuku AIDL
        ▼
Shizuku (shell, UID 2000)
        │
        │  app_process
        ▼
Target process
  ├── XposedHook.main()          bootstrap entry
  ├── HookEngine                 backend selection
  ├── HookDispatcher             per-method backend chain
  ├── ModuleLoader               dex load + entry invocation
  ├── ResourceHooking            IXposedHookInitPackageResources
  └── markers written to         /data/user/0/com.android.shell/files/.syscall_cache/hooked/
```

The manager never touches the target process directly. Everything passes through Shizuku, which is the only component that has the privilege to launch `app_process` in the first place.

---

## The multi-backend dispatcher

Not every method can be hooked the same way. The dispatcher tries a fixed order and the first backend that succeeds wins.

**Pine AUTO mode** is the primary engine. It handles object arguments, after-hooks, and most method shapes.

**Pine REPLACEMENT mode** catches methods Pine AUTO rejects, and `XC_MethodReplacement` callbacks.

**Amiru** is the new per-method stub engine (see below). It is reached when Pine declines a method or is unavailable.

**Native** is the existing `libshizuposed.so` shared-dispatcher engine. It is the last-resort ART engine.

**Instrumentation** covers Application and Activity lifecycle only.

**Proxy** covers interface methods that Pine cannot reach.

**Noop** always succeeds and does nothing. It is the last resort so module load does not abort when a single method cannot be hooked.

The order matters. On a device with a healthy Pine, neither Amiru nor the native shim ever runs a hook. On a device where Pine is broken by a ROM or an Android version shift, Amiru becomes the primary ART engine for the methods it can reach, and the native shim catches whatever Amiru declines.

`Noop` deserves a note: it reports success without installing anything. This is deliberate — a module that hooks ten methods and finds one it cannot hook should not abort the other nine. But it also means a module can appear to have loaded successfully while installing zero real hooks. If you are debugging why a module "runs" but does not work, check whether the dispatcher silently fell through to `Noop`.

---

## Amiru — the per-method stub engine

Amiru is a native ART hooking engine introduced in v3.9. Its design goal is *future-proofing*: where Pine hardcodes assumptions about ART's memory layout, Amiru probes the layout at runtime and adapts. On an Android version where Pine's assumptions no longer hold, Amiru is the piece that keeps hooking working without a rebuild.

### How it differs from the existing native shim

The original `libshizuposed.so` uses one shared C dispatcher for all hooks. Every hooked method jumps to the same entry point, and the entry point figures out which method it came from by looking at a register. This is compact, but it means every hook pays the same dispatch cost and the shared entry point cannot easily specialize per method.

Amiru generates a **unique 128-byte ARM64 stub per hooked method**. The stub is emitted into an executable page at hook time and contains exactly the sequence needed for that method's signature: save the incoming registers, call a shared C helper, then either return a replacement value or tail-branch to the original entry point. This makes the fast path shorter, and it lets Amiru eventually support object arguments and after-hooks in a way the shared-dispatcher model cannot.

### How it detects ART's layout

The same strategy as the existing native shim, extended:

1. Resolve the `ArtMethod` addresses of two calibration methods, `Object.hashCode()` and `Object.toString()`, via their `jmethodID`s. On ART, a `jmethodID` *is* the `ArtMethod*`.
2. Walk the first 64 bytes of each `ArtMethod`, looking for a pointer-sized value that lands in an executable memory range (checked against `/proc/self/maps`).
3. Require both methods to agree on the same offset. If they disagree, Amiru refuses to hook anything.
4. Cache the result for the process lifetime.

This means the same `libamiru.so` works on Android 10, 15, and later without a rebuild, as long as ART's basic `ArtMethod` shape holds. If it ever fails to find a candidate, it degrades by refusing hooks — the dispatcher chain falls through to the next backend — rather than corrupting memory.

### What Amiru supports in v0.1

- **Static methods with primitive parameters.** `int`, `long`, `float`, `double`, `boolean`, `byte`, `char`, `short`.
- **Primitive return-value replacement.** A callback can set a replacement return value and the caller sees it.
- **Before-hooks.** `beforeHookedMethod` runs before the original.
- **Per-method stubs.** Each hook gets its own executable stub, allocated from an mmap'd pool shared across all hooks in the process.
- **Graceful fallback.** Any method Amiru cannot handle causes the dispatcher to try the next backend. Modules do not fail because Amiru declined a method.

### What Amiru does not support yet

- **Object arguments.** `String`, `Bundle`, `Context`, and any other object parameter arrive as `null` in the callback. The raw register holds a JVM-internal reference, not a `jobject` handle, and reconstructing one requires per-ABI native work. Amiru rejects such methods up front so the dispatcher can try a backend that handles them.
- **`thisObject` for instance methods.** Same reason. Amiru rejects instance methods in v0.1.
- **After-hooks.** The stub returns or tail-branches immediately after the callback. There is no post-call hook point.
- **JIT invalidation.** Callers that were already inlined by the JIT still run the original. Pine handles this; Amiru does not.
- **Constructors.** No `ArtMethod` address in the same shape. Rejected up front.
- **Non-ARM64 ABIs.** Amiru is ARM64 only. On any other ABI the library fails to load and the backend reports unavailable.

### How Amiru fits into the dispatcher

Amiru sits **between the two Pine backends and the existing native shim**:

```
1. PineBackend
2. PineReplaceBackend
3. AmiruBackend            ← new in v3.9
4. NativeBackend
5. InstrumentationBackend
6. ProxyBackend
7. NoopBackend
```

This ordering means:

- On a device where Pine works, nothing changes.
- On a device where Pine declines a primitive static method, Amiru takes over.
- On a device where Amiru declines (object args, instance methods, unusual signatures), the existing native shim takes over.
- On a device where all three decline, `Proxy` and `Noop` catch the rest.

No existing behavior is changed. Amiru only adds capability for the cases where the existing engines fail.

### How to tell whether Amiru is active

Look for these lines in logcat:

```
I ShizuPosedAmiru: JNI_OnLoad: libamiru 0.1.0 (Amiru for ShizuPosed)
I ShizuPosedAmiru: layout probed: entry@0x20 access@0x4 ptr=8
I ShizuPosedAmiru: probe: Amiru: entry@0x20 access@0x4 ptr=8
I ShizuPosedAmiru: installed hook on static int Foo.compute(int) (slot 0, shorty I)
```

If you see `layout probe failed` or `loadLibrary failed`, Amiru is unavailable on this device and the dispatcher is using Pine and the native shim as before.

If you see `skipping instance method` or `skipping ... — non-primitive parameter`, Amiru is declining a specific method. That is the intended behavior; the dispatcher will try the next backend.

---

## The existing native shim (`libshizuposed.so`)

The original engine is still present and still sits in the dispatch chain as a fallback. It exists for three reasons:

**Redundancy.** If Pine's `.so` is missing, blocked by a ROM, or its layout probe fails on a new Android version, the native shim keeps hooks working for the methods it can reach.

**Self-containment.** It is under 1000 lines of C with no dependencies beyond `liblog` and `libdl`. It is auditable end-to-end.

**Dynamic adaptation.** Its layout probe and argument marshaling adapt at runtime. No hardcoded offset tables.

### What it supports

- Primitive argument decoding (`boolean` through `double`).
- Primitive return-value replacement.
- Inline patching of native symbols (`dlsym` results).
- Graceful degradation: returns `false` on unknown layouts rather than corrupting memory.

### What it does not support

- Object arguments (`String`, `Bundle`, etc.) — arrive as `null`.
- `thisObject` for instance methods — arrives as `null`.
- After-hooks — the entry returns or tail-branches after the callback.
- PC-relative prologues — the inline hooker refuses `adrp`, `adr`, `b`, `bl`, and `ldr literal` at the top of a target function. Only small leaf functions are hookable this way.
- Non-ARM64 ABIs.

Amiru supersedes this shim for primitive static methods. The shim remains useful for native symbol hooking and for any method where Amiru's per-stub approach is not applicable.

---

## Module loading

Standard Xposed module resolution.

1. The manager scans each installed module's APK for `assets/xposed_init` and caches the APK as a dex container next to the target's own module record.
2. The shell-side `XposedHook` reads the module JSON, checks whether the target package is in the module's scope, and loads the dex with a `DexClassLoader` whose parent is the target's classloader.
3. The entry class is resolved from `xposed_init`, or from `assets/xposed_init` read directly out of the cached dex, or from one of eight conventionally-named candidates.
4. `handleLoadPackage` is invoked. If it returns normally, the module name is added to the process's `loadedModuleNames` list.
5. After the module load loop completes, `writeHookedMarker` writes a JSON file to the shell-side marker directory recording which modules actually ran.

The marker is the source of truth for activation state.

---

## Activation state — how "Activated" actually works

There are two distinct questions a module can ask about itself.

**"Am I enabled?"** is answered by `XposedBridge.isModuleEnabled(pkg)`. The source of truth is the manager's preference store. Nothing about the target app or the hook process is involved.

**"Am I active?"** is answered by `XposedBridge.isModuleActive(pkg)`. The source of truth is the shell-side marker files written by `XposedHook`. Active means at least one in-scope process has actually loaded this module's entry class and returned from `handleLoadPackage`. It is not inferred from the enabled toggle.

### How a module's UI learns it is active

A module's own UI runs in the module's own process — the process launched by the launcher, not by ShizuPosed. That process is not hooked. It has no access to ShizuPosed's in-memory state. The only way it can learn its activation status is by asking the manager.

The chain is:

```
Module UI process
  → XposedBridge.isModuleActive(pkg)
    → LSPosedManager.isModuleActive(pkg)
      → ContentResolver.query(content://com.shizuposed.manager.status/active/<pkg>)
        → ModuleStatusProvider (in the manager's process)
          → reads /data/user/0/com.android.shell/files/.syscall_cache/hooked/*.json
          → parses each marker's moduleList
          → returns active=1 or active=0
```

This is why the provider must be world-readable. It exposes only whether a module is enabled, whether it has loaded into at least one target, which targets it has loaded into, and framework identity. None of that is sensitive, and a signature-level permission would make it unreachable to every module.

The provider caches marker scans for 3 seconds, so a module UI that polls on every screen refresh does not cause a Shizuku round-trip per query.

### If a module shows "not activated"

Check these in order.

**Has the target app been launched through ShizuPosed since the module was enabled?** Activation is not retroactive. A scoped app must be launched through ShizuPosed for the module to load into it.

**Does a marker exist for the target?**

```
adb shell su -c "ls /data/user/0/com.android.shell/files/.syscall_cache/hooked/"
```

If empty, the module never ran in any process.

**Does the marker list the module?**

```
adb shell su -c "cat /data/user/0/com.android.shell/files/.syscall_cache/hooked/<target>.json"
```

The `moduleList` array must contain the module's package name.

**Can the module's process reach the provider?**

```
adb shell content query --uri content://com.shizuposed.manager.status/info
```

Should return a row. If it does not, the provider is not exported or the module does not declare the `<queries>` entry for it.

**Is the module calling a different `XposedBridge`?** If logcat shows nothing from the `ShizuPosed` tag when the module's UI opens, the module is linking against a different copy of the shim.

---

## Compatibility shims

The `de.robv.android.xposed.*` package ships the classes modules link against:

`XposedHelpers` provides `findAndHookMethod` and reflection helpers. `XposedBridge` provides `getXposedVersion`, `isModuleEnabled`, `isModuleActive`, `getModuleScope`, and `log(...)`. `LSPosedManager` provides the LSPosed-compatible manager API. `XSharedPreferences` is a read-only `SharedPreferences` shim. `XResources` is the resource replacement object. `IXposedHookLoadPackage` and `IXposedHookInitPackageResources` are the entry points. `XC_MethodHook` and `XC_MethodReplacement` are the callback base classes. `XC_LoadPackage` and `XC_InitPackageResources` are the param classes.

The API version reported is **93** (LSPosed's generation). Modules that check `getXposedVersion() >= 82` accept ShizuPosed as compatible.

---

## Module development

Standard Xposed API. The entry point is `assets/xposed_init`, or one of the conventionally-named classes.

```java
public class MainHook implements IXposedHookLoadPackage {
    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        XposedBridge.log("Hooking " + lpparam.packageName);
        XposedHelpers.findAndHookMethod(
            "com.example.target.MainActivity", lpparam.classLoader,
            "onCreate", android.os.Bundle.class,
            new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) {
                    // ...
                }
            });
    }
}
```

### Reporting state in a module UI

```java
boolean enabled = XposedBridge.isModuleEnabled(getPackageName());
boolean active  = XposedBridge.isModuleActive(getPackageName());
String[] scope  = XposedBridge.getModuleScope(getPackageName());
```

For the module UI to reach the provider on Android 11+, the module's own `AndroidManifest.xml` must declare:

```xml
<queries>
    <provider android:authorities="com.shizuposed.manager.status" />
</queries>
```

Without that declaration, `ContentResolver.query` returns `Unknown URL` regardless of whether the provider is exported.

### Resource replacement

```java
public class Res implements IXposedHookInitPackageResources {
    @Override
    public void handleInitPackageResources(
            XC_InitPackageResources.InitPackageResourcesParam resparam) {
        resparam.res.setReplacement(R.string.some_string, "replacement");
    }
}
```

Only the programmatic form is supported. XML-level resource replacement is not implemented.

---

## Requirements

**Android 10 or newer, ARM64.**

**Shizuku 13.1.1 or newer**, running and authorized. The recommended build is the fork by thedjchi: https://github.com/thedjchi/Shizuku. Upstream Shizuku works, but the fork is what ShizuPosed is tested against and recommended for. It has better behavior under sustained shell access, which is what ShizuPosed needs when running `app_process` repeatedly across many launches.

**About 200 MB free storage.**

**No root required.**

**JDK 21**, **Gradle 9.7**, and **Android SDK 37** for building.

Prebuilt native libraries are committed under
`app/src/main/jniLibs/arm64-v8a/`, so a normal debug build does not require a
native compiler toolchain.

Shevery (a modernized Shizuku fork) is also supported. Some Shevery privileged-API paths have known issues in the current release; Shizuku upstream or the thedjchi fork is the more reliable choice at the moment.

---

## Installation

Install Shizuku (fork recommended) from https://github.com/thedjchi/Shizuku, or from Google Play or F-Droid if you prefer upstream.

Start Shizuku via ADB or via the Shizuku app's own start flow. The Shizuku app walks you through this.

Install the ShizuPosed Manager APK downloaded from the GitHub Release:

```bash
adb install -r ShizuPosed-<tag>-release.apk
```

Use the debug APK instead when testing a development build.

Open the manager. It requests Shizuku permission on first launch. Confirm it appears in Shizuku's authorized apps list.

Add a module from the **Modules** tab: tap the add button, pick the module's APK, confirm the extracted package name and entry point, save. Many modules are auto-detected if they declare themselves as Xposed modules.

Open the module's detail sheet and tap **Edit scope**. Choose the apps this module should apply to.

Then tap **Launch App under ShizuPosed** and pick one of the scoped apps. That app is now running with hooks installed.

**Activation is not retroactive.** A module's UI will show "Activated" only after at least one scoped app has been launched through ShizuPosed at least once.

---

## The manager

The UI has five sections.

**Home** shows the Framework Info card (framework version, API version, manager package, system version, device model, system ABI, Pine status, Amiru status) plus counters.

**Modules** lists installed modules with enable toggles, per-module scope selection, and a detail sheet. The detail sheet offers "Open module app", "Force stop scope", scope editing, and uninstall.

**Repo** is a placeholder for the future module repository browser.

**Logs** shows the manager's own log with search.

**Settings** has toggles for auto-start on boot, debug logging, log-to-file, service restart, cache clear, and config export.

The Framework Info card now includes an Amiru line that reports whether the native engine is available in the current process, and if so, what layout it probed. When Amiru is unavailable, the line explains why (library not loaded, probe failed, or backend declined).

---

## Building

```bash
export ANDROID_HOME=$HOME/Android/Sdk
git clone <repo>
cd ShizuPosed
gradle clean :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The build produces the manager APK, `XposedHook.dex` (compiled by the `makeDex`
Gradle task), and packages the committed native libraries.

Build both APK variants with:

```bash
gradle :app:assembleDebug :app:assembleRelease
```

The release APK is unsigned unless `keystore.properties` exists at the project
root with these properties:

```properties
storeFile=/absolute/path/to/release.jks
storePassword=...
keyAlias=...
keyPassword=...
```

Never commit `keystore.properties` or the keystore. The GitHub Actions workflow
currently publishes an unsigned release APK.

---

## Structure

```
ShizuPosed/
├── app/
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── assets/XposedHook.dex
│       ├── jniLibs/arm64-v8a/
│       │   ├── libshizuposed.so
│       │   └── libamiru.so                    ← new in v3.9
│       ├── java/
│       │   ├── com/shizuposed/manager/
│       │   │   ├── ShizuPosedManagerApp.java
│       │   │   ├── MainActivity.java
│       │   │   ├── ShizukuHelper.java
│       │   │   ├── core/
│       │   │   │   ├── XposedHook.java
│       │   │   │   ├── HookEngine.java
│       │   │   │   ├── HookDispatcher.java
│       │   │   │   ├── NativeBridge.java      (updated for Amiru)
│       │   │   │   ├── AmiruDispatcher.java   ← new in v3.9
│       │   │   │   ├── backends/
│       │   │   │   │   ├── PineBackend.java
│       │   │   │   │   ├── PineReplaceBackend.java
│       │   │   │   │   ├── AmiruBackend.java  ← new in v3.9
│       │   │   │   │   ├── NativeBackend.java
│       │   │   │   │   ├── InstrumentationBackend.java
│       │   │   │   │   ├── ProxyBackend.java
│       │   │   │   │   └── NoopBackend.java
│       │   │   │   └── ...
│       │   │   ├── status/ModuleStatusProvider.java
│       │   │   └── ...
│       │   └── de/robv/android/xposed/
│       │       ├── XposedBridge.java
│       │       ├── LSPosedManager.java
│       │       └── ...
│       └── res/
├── native/
│   ├── libshizuposed.c
│   ├── libamiru.c                             ← new in v3.9
│   └── build-termux.sh
├── libs/
│   ├── pine-0.3.0.jar
│   └── shizuku-*.jar
└── ...
```

---

## What's new in 3.9

### Added — Amiru native engine

**`libamiru.so`** — a per-method ARM64 stub engine that probes ART's `ArtMethod` layout at runtime and emits a unique stub per hooked method. Designed as a dynamic complement to Pine, not a replacement. Supports static methods with primitive parameters and primitive return-value replacement in this release.

**`AmiruBackend`** — dispatcher-level backend that slots Amiru between Pine and the existing native shim. Declines methods it cannot handle so the dispatcher falls through cleanly.

**`AmiruDispatcher`** — the Java side of the native dispatch path. Native stubs call `dispatch(slot, thisObj, args)`; the dispatcher routes to the registered `XC_MethodHook` and reports whether a replacement value was set.

**`NativeBridge` additions** — `amiruLoadLibrary`, `amiruProbeLayout`, `amiruDescribeLayout`, `amiruHookMethod`, `amiruUnhook`, `amiruGetArtMethod`, `amiruSummary`. All existing shizuposed methods are unchanged.

**Framework Info card** — now includes an Amiru status line showing whether the engine is available in the current process and what layout it probed.

### Fixed

**Foreground service crash on ColorOS and other aggressive ROMs.** `ShizuPosedService` was doing logging and authorization checks before calling `startForeground`, which could exceed Android's 5-second window and trigger `ForegroundServiceDidNotStartInTimeException`. `startForeground` is now the first thing that runs in both `onCreate` and `onStartCommand`, before any code that can block. The authorization gate moved after the FGS contract is satisfied, so a service that stops itself still satisfies the platform requirement.

**`NativeBridge` late-load path.** `libamiru.so` is loaded in the static block, but if that fails (e.g. on a process where the .so is not yet deployed), `amiruLoadLibrary()` retries on demand. This matters for the shell-side `app_process` where the library may be pushed after the JVM starts.

### Changed

**Dispatcher chain order.** Amiru now sits between `PineReplaceBackend` and `NativeBackend`. No existing backend changed behavior; the chain just has one more option in the middle.

### Known limitations in this release

Amiru v0.1 does not marshal object arguments, does not support `thisObject` for instance methods, does not run after-hooks, and does not invalidate JIT-inlined callers. Methods with these characteristics are rejected up front so the dispatcher can try Pine or the existing native shim. The engine is ARM64 only.

The existing native shim's limitations are unchanged: no object arguments, no `thisObject`, no after-hooks, no PC-relative prologue handling.

### Not changed

Hook path for existing backends, module format, API surface, activation state contract. Existing modules work unmodified.

---

## Known limitations

These are structural. They cannot be fixed without changing what ShizuPosed is.

**`system_server` is not hooked.** Hooking it requires being inside zygote, which requires root.

**`Service.onCreate` has no fallback.** `Instrumentation` does not dispatch Service lifecycle, and no native engine can install a hook from bootstrap into a process it did not launch.

**XML-level resource replacement is not supported.** Only the programmatic `setReplacement(id, value)` form.

**No hot-reload.** Relaunch the target under ShizuPosed to pick up module changes.

**ROMs that block `app_process`** under all names cannot run the framework at all.

**The Repo tab is a placeholder.** It does not fetch or install anything yet.

**Modules that require zygote-wide timing will not work.** This is the majority of modules that hook `system_server`, `PackageManagerService`, `ActivityManagerService`, or anything in `com.android.server.*`.

### Amiru-specific

- Object arguments arrive as `null`. Amiru rejects such methods up front.
- `thisObject` arrives as `null` for instance methods. Amiru rejects them up front.
- After-hooks are not dispatched.
- JIT-inlined callers are not invalidated.
- Constructors are not hookable.
- ARM64 only.

### Native shim-specific

- In-process only. No `ptrace`, no `process_vm_writev`, no cross-UID memory access.
- Object arguments and `thisObject` arrive as `null`.
- After-hooks are not dispatched.
- Inline (native symbol) hooking refuses PC-relative prologues.
- ARM64 only.

---

## License

```
Copyright 2024-2026 ShizuPosed Contributors

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
```

---

## Acknowledgements

**Rikka** — for Shizuku, the primitive that makes all of this possible.

**canyie** — for Pine, the primary ART hooking engine.

**LSPosed** — for the API generation ShizuPosed targets, and for the design of the module status provider contract.

Every module author who kept the `de.robv.android.xposed.*` API alive long enough for an alternative to matter.