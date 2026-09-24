# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

Xiaoian is an Android app (arm64 only, minSdk 28, root required) that runs Debian desktops (KDE Plasma 6/Wayland, XFCE 4/X11) in a `chroot` and displays them via vendored frontends. `README.md` covers user-facing behaviour; **`ARCHITECTURE.md` is the detailed design doc — read the relevant section before touching the root shell, terminal pty, mounts, or scripts.**

## Build

The Gradle root is `src/`, not the repo root.

```sh
git submodule update --init --recursive   # X server sources for :lorie; the build fails without them
cd src
./gradlew :app:assembleDebug              # gradlew.bat on Windows (the main dev machine)
./gradlew :app:assembleRelease            # signed only if src/keystore.properties exists (git-ignored)
```

- There are no unit/instrumented tests and no lint configuration; verification is done on a device (see "Verifying a change on the device" in ARCHITECTURE.md). Before a release, install an actual `assembleRelease` build — R8 can break JNI bindings and reflective entry points (`tools/Fetch`, `tools/Cat` are invoked by class name and need keep rules in `src/app/proguard-rules.pro`).
- JDK 21 is not configured in the repo (the CI runner is Linux): it comes from `JAVA_HOME`, Android Studio, or `org.gradle.java.home` in `~/.gradle/gradle.properties`. Never commit a JDK path to `src/gradle.properties`.
- The version comes from git (`src/app/build.gradle.kts`): `versionCode` = `git rev-list --count HEAD`, `versionName` = `git describe --tags` without the `v`. The build **refuses a shallow clone** (it would yield `versionCode` 1), so fetch full history before building. Uncommitted changes add `-dirty` to `versionName`.
- Two NDKs, deliberately: `:lorie` (X server) needs NDK **26.3** (`termuxX11NdkVersion` in `src/lorie/version.gradle`); `:anland` and `:terminal` use `nativeNdkVersion` in `src/gradle.properties`. The Anland consumer JNI builds at API 30 (`memfd_create`).
- All native code is built from source — never add prebuilt `.so` files or `jniLibs/` directories.
- Executables are shipped as `lib*.so` (`libanland.so`, `libfdhelper.so`, `libptyspawn.so`) so they are extracted executable into `nativeLibraryDir`; this depends on `useLegacyPackaging = true` in `src/app/build.gradle.kts`.

## Release and the chroot's binaries

`.github/workflows/release.yml` builds everything a release needs and publishes it together: on a `v*` tag, or a manual run with a tag (without one it is a trial run that only uploads artifacts). A tag with a `-` suffix becomes a pre-release. The phone-side scripts download the chroot files from this repository's releases (`MESA_REPO`/`ANLAND_REPO="Digheads/Xiaoian"` in the scripts), so a file name change must be matched in the scripts' `fetch_asset` patterns.

- **`apk`**: `:app:assembleRelease`, signed from the `KEYSTORE_BASE64`/`KEYSTORE_PASSWORD`/`KEY_ALIAS`/`KEY_PASSWORD` secrets (unsigned without them).
- **`mesa`**: the chroot's GPU driver, built by `mesa/build.sh` from the commit pinned in `mesa/version.txt` (the `Digheads/mesa-for-android-container` fork, because only it has the KGSL backend KDE's gallium freedreno needs). It is glibc, so it builds in a `debian:trixie` container on an arm64 runner, not with the NDK. The output is a plain `/usr` tarball.
- **`anland`**: Anland's KWin backend and XWayland, built by `anland-chroot/build.sh` (`gbp buildpackage`) from the tags pinned in `anland-chroot/version.txt`. These stay `.deb` packages on purpose, versioned above Debian's so apt never replaces them.

Both chroot packages are cached under the hash of their directory and are only rebuilt when `mesa/` or `anland-chroot/` changes. Details are in ARCHITECTURE.md ("The chroot's Mesa driver", "Anland's chroot side").

## Architecture

Three cooperating pieces:

1. **Root shell scripts** — `src/app/src/main/assets/xiaoian-wayland-kde.sh` and `xiaoian-x11-xfce.sh` own the chroot (download rootfs, install packages, mount, start, stop `-t`, lock `--lock`, retarget `-r`, uninstall). They are the authority on everything inside the chroot. The copies in `original/` are the older standalone Termux lineage and are **not** what the app runs.
2. **Display frontends** (library modules): `:anland` (`com.anland.termux`, Wayland, for KDE) and `:lorie` (`com.termux.x11`, Termux:X11 fork, for XFCE). They don't know about each other.
3. **`:app`** (`com.xiaoian.app`) drives the scripts over a root shell, parses their output, and provides the UI (Compose), foreground service, and terminal. `:terminal` is the vendored Termux emulator/view; `:shell-loader:stub` is compile-only fakes for `:lorie`'s `app_process` entry point.

Key flows in `:app`:

- **Root shell** (`shell/RootShell.kt`): exactly one long-lived `su` process for the app's life — never spawn `su` per command. `RootShell.shared` (mutex-serialised) for short commands; `RootShell.dedicated(label)` + `NO_TIMEOUT` for long ones (start/stop/uninstall). Commands are framed with per-command token markers; `exit` inside a command kills the shell (wrap in a subshell); non-trivial scripts are written to a file in `filesDir` rather than quoted inline. Refused prompts trigger a 20 s cooldown.
- **Script driving** (`service/XiaoianService.kt`, `service/ScriptEnv.kt`): the script, and any `Desktop.extraAssets` beside it (KDE's `startplasma-anland.sh`), is re-copied from assets into the infra root on every start. Scripts discover nothing themselves; `ScriptEnv` passes paths and choices as env vars (`ANLAND_BIN`, `XIAOIAN_LIB_DIR`, `XIAOIAN_ROOTFS_TARBALL`, `XIAOIAN_DISPLAY_ID`, `XIAOIAN_OPEN_SESSIONS`, …) and owns the kde/xfce infra-root/script-name decision — don't duplicate it at call sites.
- **Line protocol**: scripts print `[*]`/`[!]` human lines plus `@@PLAN`, `@@STEP`, `@@PROGRESS`, `@@DONE` machine lines, parsed by `shell/ScriptOutputParser.kt` into `service/SetupProgress.kt`. `ShellExecutor` error priority: `[!]` line > stderr > stdout tail.
- **Session end** must converge across Stop, in-desktop logout/crash (`SessionWatchdog` polls the pid every 5 s) and uninstall. Terminals in the chroot are closed **before** the script's teardown — live shells make unmount fail, and `do_uninstall` ends in `rm -rf`.
- **Terminal** (`terminal/`): the pty is opened by the app uid (`XiaoianPty`), then the already-open root shell runs `libptyspawn.so` on the slave (`setsid` + `TIOCSCTTY`) — zero extra `su` calls. Consequently exit is detected by EOF, not `waitpid`, and teardown is `RootPty.killSession()` by session id via `/proc/*/stat`. `TerminalSessions` is a process-scoped store; `sweepOrphans()` cleans up after process death. Required call order: `setTerminalViewClient()` → `setTextSize()` → `attachSession()`.

## Mount safety (high risk)

Mistakes here have wiped the phone's `/system` view mid-session. Read the "Mounts and mount propagation" section of ARCHITECTURE.md first.

- The Xiaoian tool rootfs `rbind`s `/` to `/mnt/android`; it must be self-bound + made private first, then `rslave`d right after the rbind. `SessionScripts.unmountTree()` is the **only** unmount loop (deepest first, `rslave` before each `umount`).
- Desktops bind only `/storage/emulated/0` (not `/data/media/0`, not the whole `/`), and it must stay in `CHROOT_MOUNTS`.
- Never test "is it mounted" with `/proc/mounts` for paths under the app's data dir (`/data/user/0` vs `/data/data` spelling); use `is_mountpoint` (device-number comparison). `unmountTree` matches both spellings.
- Use only shell builtins/toybox-safe commands in root scripts (no `awk`/`pkill` assumptions).

## Vendored components

`:lorie`, `:anland` and `:terminal` are re-synced from upstream by overwriting sources, then re-applying local divergences listed in `termux-x11-update.md`, `anland-update.md`, `terminal-update.md` (and `src/terminal/README.md`). An Anland update is one version across four places: the `:anland` frontend, `assets/startplasma-anland.sh`, and the `KWIN_TAG`/`XWAYLAND_TAG` pins in `anland-chroot/version.txt` (see `anland-update.md`). Keep changes inside these modules minimal and add any new divergence to the relevant guide. Easily-lost divergences: Termux:X11's `libXlorie.so` lookup via `$XIAOIAN_LIB_DIR`, and Anland's `singleInstance`/`taskAffinity`/`label` manifest attributes.

For the same reason the three settings screens (`SettingsActivity` in `:app`, Anland's, and `:lorie`'s `LoriePreferences`) are intentionally **not** merged. The only shared preference is the extra-keys bar layout (`anland_settings/extra_keys_layout`), whose store/key names live in `settings/AppPrefs.kt`.

## Non-obvious facts

- `XiaoianApplication` extends Termux:X11's `LorieApp`, which must run for the X11 frontend.
- `nonTransitiveRClass=true` project-wide, hence `:terminal`'s namespace is `com.termux.view`.
- `XiaoianService.ACTION_TERMINAL` is handled but never sent.
