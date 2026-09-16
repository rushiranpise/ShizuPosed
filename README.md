# ShizuPosed

A non-root, Xposed-compatible hook framework for Android apps launched through
Shizuku.

ShizuPosed runs selected apps through `app_process`, loads Xposed/LSPosed-style
modules inside those apps, and installs hooks before the app's normal
`Application` startup. It does not modify the system partition and does not
require root.

> ShizuPosed is a bootstrap hook framework, not a drop-in replacement for
> LSPosed. It cannot hook apps that it did not launch, zygote-wide code,
> `system_server`, or services it cannot enter.

## Contents

- [Requirements](#requirements)
- [Install](#install)
- [Use](#use)
- [Download builds](#download-builds)
- [Build locally](#build-locally)
- [Build and release with GitHub Actions](#build-and-release-with-github-actions)
- [Compatibility and limitations](#compatibility-and-limitations)
- [How it works](#how-it-works)
- [Module development](#module-development)
- [Troubleshooting](#troubleshooting)
- [Project layout](#project-layout)

## Requirements

### On the device

- Android 10 or newer
- ARM64 (`arm64-v8a`)
- Shizuku 13.1.1 or newer, running and authorized
- About 200 MB of free storage
- Root is not required

The project is tested with the [thedjchi Shizuku fork](https://github.com/thedjchi/Shizuku). Upstream Shizuku and Shevery may work, but the fork is the recommended choice for sustained shell access.

### To build from source

- JDK 21
- Gradle 9.7
- Android SDK platform `android-37.0`
- Android build tools 35.0.0

The repository includes the required ARM64 native libraries under
`app/src/main/jniLibs/arm64-v8a/`. A native compiler is not needed for a normal
build.

## Install

1. Install and start [Shizuku](https://github.com/thedjchi/Shizuku). Follow its
   instructions to start the service through wireless debugging or ADB.
2. Download a release APK from the repository's
   [Releases](https://github.com/rushiranpise/ShizuPosed/releases) page.
3. Install the release APK:

   ```bash
   adb install -r ShizuPosed-<tag>-release.apk
   ```

   The debug APK is suitable for testing. It is not the recommended build for
   normal use.
4. Open ShizuPosed and grant it Shizuku permission.
5. Open **Modules**, add a module APK, and save it.
6. Open the module details, choose **Edit scope**, and select the target apps.
7. Use **Launch App under ShizuPosed** to start a scoped target app.

A module is not considered active until a scoped app has been launched through
ShizuPosed at least once. Enabling a module does not retroactively affect apps
that are already running.

## Use

The manager contains five sections:

- **Home**: framework version, API version, device information, ABI, backend
  status, and activity counters.
- **Modules**: module installation, enable/disable state, scope selection, and
  module details.
- **Repo**: the repository browser placeholder.
- **Logs**: searchable manager logs.
- **Settings**: startup behavior, debug logging, service controls, cache
  clearing, and configuration export.

A module can use the status provider to show whether it is enabled and whether
it has loaded into a target process. On Android 11+, declare the provider in the
module manifest:

```xml
<queries>
    <provider android:authorities="com.shizuposed.manager.status" />
</queries>
```

The provider is read-only and exported specifically so module UIs can query
activation state from their own processes.

## Download builds

The [release workflow](.github/workflows/release.yml) publishes two APK assets:

- `ShizuPosed-<tag>-debug.apk`: debuggable build for testing
- `ShizuPosed-<tag>-release.apk`: minified release build

The release build is unsigned unless a signing configuration is supplied to the
build. Do not install an unsigned release APK on a device that already has a
version signed with a different key without uninstalling the existing app.

## Build locally

This repository does not currently include a Gradle wrapper. Install Gradle
9.7, configure the Android SDK, then run:

```bash
export ANDROID_HOME="$HOME/Android/Sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"

gradle clean :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Build both variants with:

```bash
gradle :app:assembleDebug :app:assembleRelease
```

The APK outputs are:

```text
app/build/outputs/apk/debug/app-debug.apk
app/build/outputs/apk/release/app-release.apk
```

The build also generates `XposedHook.dex` under the app build directory and
packages the committed native libraries. The custom `makeDex` task requires the
Android SDK path to be available through `ANDROID_HOME`, `ANDROID_SDK_ROOT`, or
`android.sdk.dir`.

On Termux, if AGP cannot start its Maven-provided AAPT2 binary, pass the local
override on the command line instead of committing a device-specific path:

```bash
gradle -Pandroid.aapt2FromMavenOverride="$PREFIX/bin/aapt2" :app:assembleDebug
```

### Signing a local release build

The release build is signed when `keystore.properties` exists at the repository
root. The file is ignored by Git and must contain:

```properties
storeFile=/absolute/path/to/release.jks
storePassword=your-store-password
keyAlias=your-key-alias
keyPassword=your-key-password
```

Never commit `keystore.properties` or the keystore file.

## Build and release with GitHub Actions

The workflow runs when:

- A tag beginning with `v` is pushed, such as `v3.9.0`; or
- It is started manually from the Actions tab with a tag input.

It installs Java 21, Android platform `android-37.0`, build tools 35.0.0, and
Gradle 9.7. It then builds both APK variants and creates a GitHub Release with
the two APKs attached.

The release APK is signed from four repository or environment secrets:

| Secret | Value |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | Base64 contents of the `.jks` file |
| `ANDROID_KEYSTORE_PASSWORD` | Keystore password |
| `ANDROID_KEY_ALIAS` | Key alias |
| `ANDROID_KEY_PASSWORD` | Key password |

The workflow decodes the keystore into the runner's temporary directory and
creates `keystore.properties` for Gradle. Debug builds remain debuggable and
are not used as the production artifact.

To create the GitHub secrets from a local keystore:

```bash
base64 -w 0 shizuposed-release.jks > shizuposed-release.jks.b64
gh secret set ANDROID_KEYSTORE_BASE64 < shizuposed-release.jks.b64
gh secret set ANDROID_KEYSTORE_PASSWORD
gh secret set ANDROID_KEY_ALIAS
gh secret set ANDROID_KEY_PASSWORD
```

The last three commands prompt for values. Never commit the keystore, the
`.b64` file, or `keystore.properties`.

## Compatibility and limitations

### Supported hook paths

- `Application.attachBaseContext`
- `Application.onCreate`
- `ContentProvider.onCreate`
- `Activity.onCreate`
- Static methods with primitive arguments through the Amiru/native engines
- Xposed-compatible module loading through `assets/xposed_init`
- Programmatic resource replacement through `IXposedHookInitPackageResources`

### Not supported

- Zygote-wide hooks
- `system_server` hooks
- `Service.onCreate` for services ShizuPosed did not launch
- Non-ARM64 devices
- Apps that are not launched through ShizuPosed
- XML-level resource replacement

Amiru and the native fallback engine currently do not support object arguments,
instance `thisObject` values, after-hooks, or reliable invalidation of methods
already inlined by JIT. Unsupported methods fall through to the next backend.

## How it works

The manager asks Shizuku to launch `app_process` for a selected target:

```text
ShizuPosed Manager
        |
        | Shizuku AIDL
        v
Shizuku shell process
        |
        | app_process
        v
Target app process
  - XposedHook bootstrap
  - module loading
  - hook dispatcher
  - target application startup
```

The dispatcher tries backends in this order:

1. Pine automatic hooks
2. Pine replacement hooks
3. Amiru per-method ARM64 stubs
4. The native `libshizuposed.so` fallback
5. Lifecycle instrumentation
6. Interface proxy hooks
7. No-op fallback

The first backend that accepts a method handles it. A backend declining a method
is not a module-load failure; the dispatcher continues down the chain.

## Module development

Modules use the familiar Xposed API. Declare an entry class in
`assets/xposed_init` and implement `IXposedHookLoadPackage`:

```java
public final class MainHook implements IXposedHookLoadPackage {
    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!"com.example.target".equals(lpparam.packageName)) {
            return;
        }

        XposedHelpers.findAndHookMethod(
                "com.example.target.MainActivity",
                lpparam.classLoader,
                "onCreate",
                android.os.Bundle.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        XposedBridge.log("MainActivity.onCreate called");
                    }
                });
    }
}
```

The compatibility package is under `de.robv.android.xposed`. The framework
reports Xposed API version 93 and provides the common load-package, method-hook,
resource-hook, preferences, and manager-status shims.

## Troubleshooting

### Shizuku permission is missing

Open Shizuku, start its service, then return to ShizuPosed and grant permission
when prompted. The manager cannot launch a target through `app_process` without
Shizuku authorization.

### A module shows as enabled but not active

Enable the module, confirm that the target package is in its scope, then launch
the target with **Launch App under ShizuPosed**. Activation is recorded only
when the module actually loads into a target process.

### A target starts without hooks

Check that the target was launched by ShizuPosed, that the module is enabled,
and that its package is scoped to the target. Then inspect the manager logs.
The native engines support ARM64 only.

### The local build cannot find `d8` or `android.jar`

Install Android platform `android-37.0` and build tools 35.0.0, then export
`ANDROID_HOME` and `ANDROID_SDK_ROOT`. The custom `makeDex` task reads those
variables while Gradle configures the project.

### A release APK will not install over an existing version

Unsigned and differently signed APKs cannot replace one another. Uninstall the
existing app or build the release with the same signing key used by the installed
version.

## Project layout

```text
ShizuPosed/
├── app/
│   ├── libs/                         Local Shizuku and Pine JARs
│   └── src/main/
│       ├── java/
│       │   ├── com/shizuposed/manager/  Manager and hook engine
│       │   └── de/robv/android/xposed/  Xposed compatibility API
│       ├── jniLibs/arm64-v8a/        libshizuposed.so and libamiru.so
│       └── res/                      Android resources and layouts
├── .github/workflows/release.yml    APK build and GitHub Release workflow
├── app/build.gradle                  Android variants and makeDex task
├── build.gradle                      Android Gradle Plugin configuration
├── gradle.properties                 Gradle and Android settings
└── settings.gradle                   Project and module configuration
```

## License

See [LICENSE](LICENSE).
