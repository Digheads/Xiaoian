# Anland (Wayland/KDE) Update Guide (Native Integration)

The integration of Anland into Xiaoian was done similarly to the `lorie` (Termux:X11) module, using a "zero-dependency" approach. This means we bypass the complexity of Android NDK C++ compilation on Windows by using the official, pre-compiled native libraries (`.so` files), while only compiling the Java frontend from source.

If the Anland project (`lfdevs/anland-termux`) releases a new version and you want to update the embedded KDE/Wayland engine in the Xiaoian app, follow these steps:

## 1. Obtain the Required Files

1. **Source Code**: Download the new Anland source code (as a zip) for the specific release (or from the main branch).
2. **Precompiled APK**: Download the official, **pre-compiled APK** file (e.g., from the GitHub Releases tab, `AnlandTermux-X.Y.Z.apk`). *Important: The APK and the downloaded source code must be from the exact same version!*

## 2. Update the Java / Frontend Source Code

1. Unzip the downloaded source code.
2. Copy and overwrite the files in your `Xiaoian/anland/src/main/` folder using the contents from the original source code's `app/src/main/` folder. Specifically:
   - `java/com/anland/termux/` folder (all Java source code)
   - `res/` folder (UI resources, icons, layouts)
   - `AndroidManifest.xml`
3. You do **NOT** need to overwrite the `anland/build.gradle` file. We use a custom, `com.android.library` formatted Gradle file that has all C++ and CMake references removed. Keep our existing `build.gradle`!

## 3. Update the C++ (JNI) Libraries

Since we bypass C++ compilation, we extract the native binaries directly from the APK:
1. Rename the downloaded `.apk` file extension to `.zip` and extract it.
2. Navigate to the extracted folder's `lib/arm64-v8a/` directory.
3. Copy all `.so` files found there (e.g., `libanland_consumer.so`, `libfdhelper.so`, etc.) into the following folder in the Xiaoian project (overwriting the old ones):
   `Xiaoian/anland/src/main/jniLibs/arm64-v8a/`
4. (Optional: If you want to support other architectures like x86_64 or armeabi-v7a, copy their respective folders into `jniLibs` as well).

### 3b. The display daemon (`libanland.so`) — do not skip this

Three native files are needed, not two:

| File | What it is |
|---|---|
| `libanland_consumer.so` | JNI library loaded by the Java frontend |
| `libfdhelper.so` | Root helper executable (named `lib*.so` so it gets extracted with the execute bit) |
| `libanland.so` | **The display daemon.** Also an executable, not a library |

Upstream builds the daemon from `jni/daemon/anland.c` (see `jni/CMakeLists.txt`,
which sets `PREFIX "lib"` / `SUFFIX ".so"` on the `anland` target). Check
`lib/arm64-v8a/` in the extracted APK first and copy it like the others.

If it is not in the APK, build it from the source drop — it only needs two C
files and `liblog`, so no CMake or Gradle NDK setup is required:

```bash
NDK="$ANDROID_SDK_ROOT/ndk/29.0.14206865/toolchains/llvm/prebuilt/windows-x86_64/bin"
cd anland/src/main/jni

"$NDK/aarch64-linux-android28-clang" -O2 -Wall -fPIE -pie     -I anland_core/common     daemon/anland.c anland_core/common/socket_utils.c     -llog -o /tmp/libanland.so

"$NDK/llvm-strip" --strip-unneeded /tmp/libanland.so     -o ../jniLibs/arm64-v8a/libanland.so
```

Use the API level matching the project's `minSdk` (28). Verify with
`file libanland.so` — it must say *ELF 64-bit … ARM aarch64 … pie executable*.

**Why the odd name:** `/data/data` is mounted non-executable (W^X), so a binary
there cannot be run. Android's packager extracts APK entries matching `lib*.so`
into the app's native library directory with the execute bit set. That
extraction only happens with `useLegacyPackaging = true`, which must be set in
the **application** module (`app/build.gradle.kts`) — a library module's own
packaging options do not affect the final APK. With the AGP 8 default
(`extractNativeLibs=false`), `nativeLibraryDir` points inside the APK and
neither `libanland.so` nor `libfdhelper.so` can be exec'd.

`xiaoian-wayland-kde.sh` takes the daemon path from `$ANLAND_BIN`, which the app
passes in via `ScriptEnv.prefix()`. The script does no discovery of its own — it
is never run by hand. It starts the daemon itself; the app must not, or the
script's own restart and socket-wait logic fights with it.

## 4. Restore Xiaoian-Specific Modifications (CRITICAL!)

Because you updated the Java files and the Manifest from the original source, you must reapply a few vital modifications that we made for library integration:

### A. Clean up AndroidManifest.xml
Open the updated `anland/src/main/AndroidManifest.xml` file.
The original code is meant for a standalone application, but in our case, it's an embedded Library module. Therefore, remove the following attributes from the `<application>` tag (otherwise it will conflict with our main app):
- `android:label="..."`
- `android:theme="..."`
- `android:allowBackup="..."`

Furthermore, find the `MainActivity` intent-filter block and delete the Launcher category, so Anland doesn't create a separate icon in the phone's app drawer:
```xml
<!-- DELETE THIS LINE: -->
<category android:name="android.intent.category.LAUNCHER" />
```

### B. Fix KeyEvent Errors (Optional, version dependent)
If Anland uses `compileSdk = 37` or a newer beta SDK, and our project uses an older one (e.g., 35), keys from `KEYCODE_F13` and above will throw compilation errors. If this happens, you must comment out or replace the F13-F24 keycodes in both `KeyCodeMapper.java` and `MainActivity.java`.

### C. Fix BuildConfig Errors
Because we are using a Library module, the `BuildConfig.COMPATIBLE` and `BuildConfig.VERSION_NAME` variables expected by the original code might not be generated.
- In `MainActivity.java`, change all `BuildConfig.COMPATIBLE` references to `false`.
- In `SettingsActivity.java`, replace the `BuildConfig.VERSION_NAME` line with a hardcoded version string (e.g., `"5.13.3"`).

## 5. Verification

Run `Clean Project` and `Rebuild Project` in Android Studio. If the build is successful, the Xiaoian app will use the updated Anland engine and its native AAudio system for the KDE environment on the next launch!
