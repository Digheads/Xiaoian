# Termux:X11 Update Guide (Native Integration)

Because the Termux:X11 engine was integrated into Xiaoian in a "zero-dependency" way, we use precompiled libraries (`.so` files) to avoid native C++ compilation on Windows. The Java frontend, however, is compiled from source in the `lorie` module.

If Termux:X11 releases a new version and you want to update the integration, follow these steps:

## 1. Obtain the Required Files

1. **Source Code**: Download the new Termux:X11 source code (as a zip) for the specific version (or from the master branch).
2. **Precompiled APK**: Download the **precompiled APK** file corresponding to that exact version or commit (e.g., from GitHub Actions artifacts or the Releases tab). *Important: The APK and the source code must be built from the exact same commit!*

## 2. Update the Java / Frontend Source Code

1. Unzip the downloaded source code.
2. Copy and overwrite the files in your `Xiaoian/lorie/src/main/` folder with the contents from the downloaded source code's `app/src/main/` folder. Specifically:
   - `java/com/termux/x11/` folder (all Java source code)
   - `res/` folder (all UI resources, layouts, and strings)
   - `AndroidManifest.xml`
   - Also look for and copy `templates/` or similar folders if they exist (e.g. `src/main/templates`).
3. Update `lorie/build.gradle` using the original source code's `app/build.gradle` as a reference, but **make sure to keep our custom modifications** (see step 4).

## 3. Update the C++ Precompiled Libraries (JNI Libs)

Since we cannot compile the C++ code on Windows using the NDK, we extract the precompiled libraries directly from the APK:
1. Rename the downloaded `.apk` file extension to `.zip` and extract it.
2. Navigate to the extracted folder's `lib/arm64-v8a/` directory.
3. Copy all `.so` files found there (e.g., `libXlorie.so`, `libdatastore_shared_counter.so`, etc.) into your Xiaoian project here:
   `Xiaoian/lorie/src/main/jniLibs/arm64-v8a/`
4. (If you want to support other architectures like `armeabi-v7a` or `x86_64`, copy them into their respective folders as well).

## 4. Restore Xiaoian-Specific Modifications (CRITICAL!)

We made some essential fixes to the original Termux:X11 `build.gradle` file and code, which must be reapplied to `lorie/build.gradle` after every update:

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
*Tip: You can safely remove the C/C++ build steps (CMake, NDK) from `lorie/build.gradle`, since we are pulling them in via jniLibs!*

## 5. Verification
- `XiaoianApplication.kt` must continue to extend the `com.termux.x11.LorieApp` class.
- The `xiaoian-x11-xfce.sh` script relies on the `dalvikvm -cp ... com.termux.x11.CmdEntryPoint` command to launch Xwayland. This interface rarely changes, but if it fails, check if the `CmdEntryPoint` class was moved or renamed.

Once you have completed all these steps, run a `Clean Project` + `Rebuild Project` in Android Studio (or via Gradle), and Xiaoian will immediately start using the new Termux:X11 engine!
