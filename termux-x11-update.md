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

## 3. Update the C sources and the submodules

`libXlorie.so` is built here, from `src/lorie/src/main/cpp`, so there is nothing
to lift out of a release APK:

1. Replace the contents of `src/lorie/src/main/cpp/lorie`, `recipes` and
   `patches` from the new source drop.
2. Move each submodule (`xserver`, `libx11`, `pixman`, `xkbcomp` and the rest)
   to the commit the new version pins. Upstream's own `.gitmodules` and its tree
   list them; `git -C <submodule> checkout <sha>` for each, then commit the
   gitlinks.
3. Build with `./gradlew.bat :lorie:externalNativeBuildDebug` before anything
   else, and keep the three local build fixes (see step 4E).

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
*Keep the `externalNativeBuild` blocks and `ndkVersion termuxX11NdkVersion`:
the X server is built here. The NDK version lives in `src/lorie/version.gradle`
and has to stay at 26.3 — newer bionic headers break libx11.*

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

### D. The X server must not kill itself when the window is closed (CRITICAL)

In `LorieApp.onBroadcastReceive`, the `ACTION_START` branch must treat a binder
it already holds as the same X server announcing itself again, not as a second
one. Look for the `XIAOIAN` comments around `boolean known = binder ==
activeService || binder == pendingConnection;`.

**What upstream does and why it breaks here.** The X server re-broadcasts
`ACTION_START` once a second for as long as nothing is connected to it
(`CmdEntryPoint.sendBroadcastDelayed`). The receiver is declared in the
manifest, so it runs whether or not a `MainActivity` exists. Upstream's guard
is:

```java
if ((activeService != null && activeService.isBinderAlive())
        || (pendingConnection != null && pendingConnection.isBinderAlive())) {
    ... reportFatalError("Termux:X11 already has an active X server connection.");
```

With the desktop running but its window closed there is no activity, so the
first broadcast is parked in `pendingConnection` and the **second one, from the
same server**, matches that guard. `reportFatalError` goes straight to
`FatalError()` in `cmdentrypoint.cpp`, which exits the X server. Every X client
dies with it, `xfce4-session` exits, the session wrapper runs its teardown, and
the whole desktop stops roughly two seconds after the user closed the window.
Reopening it quickly only races the same broadcast, which is why the window came
back but never reconnected.

Upstream does not hit this because there the frontend is normally open whenever
the server runs. Xiaoian deliberately lets a desktop keep running with its
window closed, so the fix has to be re-applied after every sync. Two symptoms to
test for: closing the XFCE window must leave the session running, and reopening
it must reconnect to the same desktop.

The same edit also moves `linkToDeath` behind the `known` check — without that
the retry loop registered a death recipient every second.

### E. The three local build fixes (CRITICAL on Windows)

Upstream only ever builds this on Linux, so `src/lorie/src/main/cpp` carries
three changes of ours. All of them are in `CMakeLists.txt`, plus one new file:

1. **`recipes/host_tool.cmake`** builds and runs the `makekeys` generator on
   the machine doing the build. Upstream calls `/usr/bin/gcc` and redirects
   with `>`, neither of which exists here; this one works with gcc or with
   MSVC. `xkbcomp.cmake` calls it.
2. **`target_apply_patch`** calls `patch` directly instead of going through
   `bash -c "... || ..."`, where Windows found another bash and split the paths
   on their spaces — silently skipping every patch, which shows up much later
   as a missing `GL/gl.h`.
3. **The `case-shim` include directory**, for bionic's `#include <xlocale.h>`
   finding libx11's `X11/Xlocale.h` on a case-insensitive filesystem.

`recipes/xserver.cmake` also links `Xlorie` with `-Wl,-s`, or the library goes
into the APK at 20 MB.

## 5. Verification
- `XiaoianApplication.kt` must continue to extend the `com.termux.x11.LorieApp` class.
- The `xiaoian-x11-xfce.sh` script relies on the `dalvikvm -cp ... com.termux.x11.CmdEntryPoint` command to launch Xwayland. This interface rarely changes, but if it fails, check if the `CmdEntryPoint` class was moved or renamed.

Once you have completed all these steps, run a `Clean Project` + `Rebuild Project` in Android Studio (or via Gradle), and Xiaoian will immediately start using the new Termux:X11 engine!
