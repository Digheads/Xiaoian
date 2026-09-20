# Termux:X11 Update Guide (Native Integration)

Because the Termux:X11 engine was integrated into Xiaoian in a "zero-dependency" way, we use precompiled libraries (`.so` files) to avoid native C++ compilation on Windows. The Java frontend, however, is compiled from source in the `lorie` module.

If Termux:X11 releases a new version and you want to update the integration, follow these steps:

## 1. Obtain the Required Files

1. **Source Code**: Download the new Termux:X11 source code (as a zip) for the specific version (or from the master branch).
2. **Precompiled APK**: Download the **precompiled APK** file corresponding to that exact version or commit (e.g., from GitHub Actions artifacts or the Releases tab). *Important: The APK and the source code must be built from the exact same commit!*

## 2. Update the Java / Frontend Source Code

1. Unzip the downloaded source code.
2. Copy and overwrite the files in your `src/lorie/src/main/` folder with the contents from the downloaded source code's `app/src/main/` folder. Specifically:
   - `java/com/termux/x11/` folder (all Java source code)
   - `res/` folder (all UI resources, layouts, and strings)
   - `AndroidManifest.xml`
   - Also look for and copy `templates/` or similar folders if they exist (e.g. `src/main/templates`).
3. Update `src/lorie/build.gradle` using the original source code's `app/build.gradle` as a reference, but **make sure to keep our custom modifications** (see step 4).

## 3. Update the C++ Precompiled Libraries (JNI Libs)

Since we cannot compile the C++ code on Windows using the NDK, we extract the precompiled libraries directly from the APK:
1. Rename the downloaded `.apk` file extension to `.zip` and extract it.
2. Navigate to the extracted folder's `lib/arm64-v8a/` directory.
3. Copy all `.so` files found there (e.g., `libXlorie.so`, `libdatastore_shared_counter.so`, etc.) into your Xiaoian project here:
   `src/lorie/src/main/jniLibs/arm64-v8a/`
4. (If you want to support other architectures like `armeabi-v7a` or `x86_64`, copy them into their respective folders as well).

## 4. Restore Xiaoian-Specific Modifications (CRITICAL!)

We made some essential fixes to the original Termux:X11 `build.gradle` file and code, which must be reapplied to `src/lorie/build.gradle` after every update:

### A. Fix Gradle Tasks
In the original Termux:X11 `build.gradle`, the `generatePrefs` and `generateShortcuts` tasks incorrectly run during the configuration phase. You must wrap their execution code inside a `doLast { ... }` block!
```gradle
    tasks.register("generatePrefs") {
        doLast {
            // original prefs code...
        }
    }
    
    tasks.register("generateShortcuts") {
        doLast {
            // original shortcuts code...
        }
    }
```

### B. Correct the Kotlin Stdlib Version
The original code often references an incorrect or non-existent Kotlin version (e.g., `2.4.20`). Find it in the `dependencies` block and change it to match your project's version (e.g., `2.0.0`):
```gradle
dependencies {
    implementation "org.jetbrains.kotlin:kotlin-stdlib-jdk8:2.0.0"
}
```
*Tip: You can safely remove the C/C++ build steps (CMake, NDK) from `src/lorie/build.gradle`, since we are pulling them in via jniLibs!*

### C. Load `libXlorie.so` from the extracted library directory (CRITICAL)

`CmdEntryPoint.initEntryPoint()` upstream resolves the native library as a
resource **inside** the APK and `System.load()`s that path:

```java
String path = "lib/" + Build.SUPPORTED_ABIS[0] + "/libXlorie.so";
URL res = loader.getResource(path);
String libPath = res != null ? res.getFile().replace("file:", "") : null;
```

The linker can only dlopen a library straight out of an APK while native libs
are stored **uncompressed** — that is why upstream sets
`useLegacyPackaging false`. Xiaoian cannot: the same APK ships executables named
`lib*.so` (`libanland.so`, `libfdhelper.so`) which only become runnable files
when the packager **extracts** them, so `app/build.gradle.kts` sets
`useLegacyPackaging = true`. The APK entry is then compressed, `System.load()`
throws, and `initEntryPoint` calls `System.exit(134)` — the X server dies before
creating its socket and the script reports
*"X server did not create socket in time"*.

After updating `CmdEntryPoint.java`, reapply the lookup: try the extracted copy
first, keep the upstream APK read as the fallback.

```java
String libPath = extractedLibPath();

if (libPath == null) {
    String path = "lib/" + Build.SUPPORTED_ABIS[0] + "/libXlorie.so";
    ClassLoader loader = CmdEntryPoint.class.getClassLoader();
    URL res = loader != null ? loader.getResource(path) : null;
    libPath = res != null ? res.getFile().replace("file:", "") : null;
}

Log.i("CmdEntryPoint", "loading libXlorie.so from " + libPath);
```

with this helper next to it (and `import java.io.File;`):

```java
private static String extractedLibPath() {
    String dir = System.getenv("XIAOIAN_LIB_DIR");
    if (dir != null && !dir.isEmpty()) {
        File lib = new File(dir, "libXlorie.so");
        if (lib.exists())
            return lib.getAbsolutePath();
    }

    String cp = System.getenv("CLASSPATH");
    if (cp == null)
        return null;
    for (String entry : cp.split(":")) {
        File apk = new File(entry);
        File[] abis = new File(apk.getParentFile(), "lib").listFiles();
        if (abis == null)
            continue;
        for (File abi : abis) {
            File lib = new File(abi, "libXlorie.so");
            if (lib.exists())
                return lib.getAbsolutePath();
        }
    }
    return null;
}
```

> **Do not** reach for `ctx.getApplicationInfo().nativeLibraryDir` here. `ctx` is
> `ActivityThread.getSystemContext()` — the *system* context — so it describes
> the `android` package, not this app, and its `nativeLibraryDir` points at
> `/system/lib64`. The lookup silently falls through to the APK read and the
> server dies exactly as before.

`$XIAOIAN_LIB_DIR` is exported by the app (`ScriptEnv.kt`) and passed down by
`xiaoian-x11-xfce.sh`, which also derives it from `$APK_PATH` if it is missing.

`LorieView` and Anland's `MainActivity` use plain `System.loadLibrary`, which
works in either packaging mode — this one call site is the only thing that cares.

## 5. Verification
- `XiaoianApplication.kt` must continue to extend the `com.termux.x11.LorieApp` class.
- The `xiaoian-x11-xfce.sh` script relies on the `dalvikvm -cp ... com.termux.x11.CmdEntryPoint` command to launch Xwayland. This interface rarely changes, but if it fails, check if the `CmdEntryPoint` class was moved or renamed.

Once you have completed all these steps, run a `Clean Project` + `Rebuild Project` in Android Studio (or via Gradle), and Xiaoian will immediately start using the new Termux:X11 engine!
