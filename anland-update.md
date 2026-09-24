# Anland (Wayland/KDE) Update Guide (Native Integration)

The integration of Anland into Xiaoian was done similarly to the `lorie` (Termux:X11) module, using a "zero-dependency" approach. This means we bypass the complexity of Android NDK C++ compilation on Windows by using the official, pre-compiled native libraries (`.so` files), while only compiling the Java frontend from source.

If the Anland project (`lfdevs/anland-termux`) releases a new version and you want to update the embedded KDE/Wayland engine in the Xiaoian app, follow these steps:

## 1. Obtain the Required Files

1. **Source Code**: Download the new Anland source code (as a zip) for the specific release (or from the main branch).
2. **Precompiled APK**: Download the official, **pre-compiled APK** file (e.g., from the GitHub Releases tab, `AnlandTermux-X.Y.Z.apk`). *Important: The APK and the downloaded source code must be from the exact same version!*

## 2. Update the Java / Frontend Source Code

1. Unzip the downloaded source code.
2. Copy and overwrite the files in your `src/anland/src/main/` folder using the contents from the original source code's `app/src/main/` folder. Specifically:
   - `java/com/anland/termux/` folder (all Java source code)
   - `res/` folder (UI resources, icons, layouts)
   - `AndroidManifest.xml`
3. You do **NOT** need to overwrite the `anland/build.gradle` file. We use a custom, `com.android.library` formatted Gradle file that has all C++ and CMake references removed. Keep our existing `build.gradle`!

## 3. Update the C (JNI) sources

The natives are built here, from `src/anland/src/main/jni`, so there is nothing
to lift out of a release APK. Replace the sources from the new drop and let
Gradle build them:

| File | Built from | What it is |
|---|---|---|
| `libanland_consumer.so` | `native_consumer.c`, `native_audio.c`, `camera_service.c`, `anland_core/…` | JNI library loaded by the Java frontend |
| `libfdhelper.so` | `fd_helper.c` | Root helper executable (named `lib*.so` so it gets extracted with the execute bit) |
| `libanland.so` | `daemon/anland.c` | **The display daemon.** Also an executable, not a library |

`src/main/jni/CMakeLists.txt` defines all three, and `build.gradle` points
`externalNativeBuild` at it. If upstream's CMakeLists gains a source file or a
library, mirror it there. Keep `-DANDROID_PLATFORM=android-30` in
`build.gradle`: the consumer calls `memfd_create()`, which bionic only declares
from API 30 on, even though the module's `minSdk` is 28.

Check afterwards that every `native` method in `Native.java` and `Clipboard.java`
still exists in the C sources — the compiler will not tell you, the app will,
by crashing on first use.

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

## 5. Update the chroot side to the same version

The frontend is only half of Anland. Three more pieces run inside the Debian
chroot, and they have to come from the **same release** as the frontend: the
helper and the frontend talk to each other, and the helper starts the KWin
backend. None of them is fetched from upstream at run time.

| Piece | Where it lives here | How to update it |
|---|---|---|
| `startplasma-anland.sh` (Plasma start helper) | `src/app/src/main/assets/`, shipped in the APK | Replace it with `scripts/startplasma-anland.sh` from the new release's tag |
| KWin with the Anland backend (`kwin_<tag>.zip`) | built by CI from `Digheads/kwin`, attached to every Xiaoian release | Set `KWIN_TAG` in `anland-chroot/version.txt` to the new release's Debian tag (`anland-X.Y-debian-…`) |
| XWayland (`xwayland_…_arm64.deb`) | built by CI from `Digheads/xwayland`, attached to every Xiaoian release | Set `XWAYLAND_TAG` the same way |

First sync the forks (`Digheads/anland-termux`, `Digheads/kwin`,
`Digheads/xwayland`) with upstream, tags included, so the new tags exist
there. Changing `anland-chroot/version.txt` makes the next release rebuild both
packages.

If a file name changes, check the patterns in `xiaoian-wayland-kde.sh`
(`fetch_asset "$ANLAND_REPO" ...`). The XWayland one names an exact version on
purpose: it has to be the build made for Debian trixie.

## 6. Verification

Run `Clean Project` and `Rebuild Project` in Android Studio. If the build is successful, the Xiaoian app will use the updated Anland engine and its native AAudio system for the KDE environment on the next launch!
