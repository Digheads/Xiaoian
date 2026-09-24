# Xiaoian — architecture and development guide

For what the app *does*, see the [README](README.md); for the standalone scripts, see
[original/README.md](original/README.md). For keeping the vendored frontends and
the terminal up-to-date, see [Updating the vendored components](#updating-the-vendored-components).

---

## The shape of the thing

Xiaoian is an Android app that runs Debian desktops in a `chroot` and shows them
on screen. Three things have to cooperate:

1. **A shell script per desktop**, running as root, which owns the chroot: it
   downloads the rootfs, installs packages, mounts, starts the session and tears
   it all down again. The scripts are the authority on everything inside the chroot.
2. **An Android frontend per desktop**, which draws the pixels: a Wayland
   compositor client for KDE, an X server for XFCE. Both are vendored into the
   APK as library modules, so nothing has to be installed separately.
3. **The app itself**, which drives the scripts over a root shell, parses what
   they print, and puts a UI on it.

The app and the scripts talk over stdout in a small line protocol; the scripts
and the frontends talk over sockets and binders that the scripts set up.

```
  ┌────────────┐   commands + stdout     ┌──────────────────┐
  │   :app     │ ◄────────────────────►  │  xiaoian-*.sh    │  (root)
  │            │      RootShell / su     │   owns the chroot │
  └─────┬──────┘                         └────────┬─────────┘
        │ Intent / broadcast                      │ socket / binder
        ▼                                         ▼
  ┌────────────┐                          ┌──────────────────┐
  │  :anland   │  Wayland  ◄───────────►  │  KWin / plasma   │
  │  :lorie    │  X11      ◄───────────►  │  Xorg / xfce     │
  └────────────┘                          └──────────────────┘
```

---

## Repository layout

```
.
├── README.md                 app: what it does, how to use it
├── ARCHITECTURE.md           this file
├── LICENCE                   GPLv3
├── termux-x11-update.md      how to re-integrate a new Termux:X11 release
├── anland-update.md          how to re-integrate a new Anland release
├── terminal-update.md        how to re-vendor the Termux terminal
├── original/                 the standalone Termux scripts + their README
├── mesa/                     the chroot's GPU driver: pinned source + build script
├── .github/workflows/        release.yml: APK + Mesa package, built and published together
└── src/                      everything Gradle
    ├── settings.gradle.kts   :app :lorie :anland :terminal :shell-loader:stub
    ├── app/                  the application
    ├── anland/               Wayland frontend  (fork of lfdevs/anland-termux)
    ├── lorie/                X11 frontend      (fork of termux/termux-x11)
    ├── terminal/             terminal emulator (vendored from termux/termux-app)
    └── shell-loader/         support for the X server's app_process entry point
```

The Gradle root is `src/`, not the repository root. Build from there.

### Modules

| Module | Namespace | What it is |
|---|---|---|
| `:app` | `com.xiaoian.app` | The application: UI, service, root shell, script driver, terminal screen |
| `:anland` | `com.anland.termux` | Wayland display frontend for KDE. A fork; carries its own JNI (`libanland_consumer.so`) and two helper executables |
| `:lorie` | `com.termux.x11` | X11 display frontend for XFCE. A fork of Termux:X11; carries `libXlorie.so` |
| `:terminal` | `com.termux.view` | Termux's terminal emulator and view, vendored. See [src/terminal/README.md](src/terminal/README.md) |
| `:shell-loader:stub` | — | Compile-only fakes of `android.app.ActivityThread` / `ContextImpl`, which `:lorie` needs to build its `app_process` entry point |

`:app` depends on all of them; the frontends do not know about each other.

---

## Building

```sh
git clone --recursive …          # or: git submodule update --init --recursive
cd src
./gradlew.bat :app:assembleDebug          # or :app:assembleRelease
../tools/platform-tools/adb.exe install -r app/build/outputs/apk/debug/app-debug.apk
```

Java 21, compileSdk 35, minSdk 28, arm64 only. The JDK path is not in the repo:
the same build runs on a Linux CI runner, where a Windows path in
`src/gradle.properties` would stop it before it starts. Locally it comes from
`JAVA_HOME`, Android Studio, or `~/.gradle/gradle.properties`.

The version is read from git in `app/build.gradle.kts`: `versionCode` is
`git rev-list --count HEAD`, `versionName` is `git describe --tags`. A shallow
clone would count only what it fetched, so the build refuses one instead of
quietly producing `versionCode` 1.

### Native code is built from source

Every `.so` in the APK is built by Gradle from the sources in this tree. There
are no prebuilt binaries and no `jniLibs/` directories: those drift from the
sources beside them, and once did — `libanland_consumer.so` was missing a
symbol its Java side declared, and crashed on first use.

| Module | CMake | NDK | Produces |
|---|---|---|---|
| `:anland` | `src/main/jni/CMakeLists.txt` | `nativeNdkVersion` | `libanland_consumer.so`, `libanland.so`, `libfdhelper.so` |
| `:terminal` | `src/main/jni/CMakeLists.txt` | `nativeNdkVersion` | `libtermux.so`, `libptyspawn.so` |
| `:lorie` | `src/main/cpp/CMakeLists.txt` | `termuxX11NdkVersion` | `libXlorie.so` — the X server and the X libraries it needs |

The NDK versions are in `src/gradle.properties` and `src/lorie/version.gradle`,
and they are deliberately different. **The X server only builds with NDK 26.3**;
newer bionic headers annotate `locale_t` in a way libx11 predates. The other two
are small and modern and use the current NDK.

API levels are not interchangeable either: the Anland consumer is built at **API
30** (`-DANDROID_PLATFORM=android-30`) because it calls `memfd_create()`, which
bionic only declares from 30 on. Everything else follows the module's `minSdk`.

### The X server's sources are submodules

`:lorie` carries the glue (`src/main/cpp/lorie`), the CMake recipes and the
patches; the X server, libx11, pixman, xkbcomp and a dozen more are git
submodules pinned to the commits upstream Termux:X11 builds against. Without
`--recursive` they are empty directories and CMake stops at the first missing
header. The patches under `src/main/cpp/patches` are applied to those working
trees at configure time, which is why the submodules are `ignore = dirty`.

Three things in that build are ours, and all three exist because upstream only
ever builds on Linux:

- **A host compiler.** `makekeys` runs on the machine doing the build, not on
  the phone. Upstream calls `/usr/bin/gcc` and redirects with `>`; here
  `recipes/host_tool.cmake` compiles and runs it, with gcc or with MSVC (found
  through Visual Studio's `vswhere`).
- **bison**, for xkbcomp's parser. On Windows there is none; drop
  [winflexbison](https://github.com/lexxmark/winflexbison) into `tools/`
  (git-ignored) and the build finds `tools/winflexbison/win_bison.exe`.
- **A case-insensitive filesystem shim.** On Windows, bionic's
  `#include <xlocale.h>` finds libx11's `X11/Xlocale.h`, which includes
  `locale.h` right back, and every libx11 file fails on an undefined
  `locale_t`. The top-level `CMakeLists.txt` puts a copy of bionic's
  `xlocale.h` first in the include path.

`libXlorie.so` is linked with `-Wl,-s`. It is built `RelWithDebInfo` whatever
the app's build type, so Gradle's own strip step does not touch it, and it
would otherwise be 20 MB in the APK instead of 3.5.

### Executables shipped as `lib*.so`

`/data/data` is not executable, so a plain binary dropped there cannot be run.
The trick the project uses throughout is to name executables `lib*.so` and let
the packager extract them into `nativeLibraryDir` with the execute bit —
`libanland.so`, `libfdhelper.so` and `libptyspawn.so` are all executables, not
libraries. This only works with `useLegacyPackaging = true`, which is set both
in the library modules and, decisively, in `:app` — a library module's packaging
options do not affect the final APK.

---

## Updating the vendored components

Three parts of this app are other people's code, kept in tree. Each has its own
guide, because each is integrated a different way:

| Component | Guide | How it is integrated |
|---|---|---|
| Termux:X11 (`:lorie`) | [termux-x11-update.md](termux-x11-update.md) | Java and C from source; the X sources it needs are submodules |
| Anland (`:anland`) | [anland-update.md](anland-update.md) | Java and JNI from source |
| Termux terminal (`:terminal`) | [terminal-update.md](terminal-update.md) | Fully vendored — Java *and* the native source |

All three build here, so a Java side and a native side can no longer disagree
about a symbol. Updating one means replacing its source drop and, for `:lorie`,
moving the submodules to whatever commits the new version pins.

One thing that applies whichever guide you are following:

- **The frontends carry local modifications**, listed in each guide, and they
  have to be re-applied after every update. The ones most likely to be lost are
  Termux:X11's `libXlorie.so` lookup through `$XIAOIAN_LIB_DIR` and Anland's
  `singleInstance` / `taskAffinity` / `label` manifest attributes — without the
  latter the KDE desktop stops opening in a window of its own.

---

## The root shell

Every root command in the app goes through
[`shell/RootShell.kt`](src/app/src/main/java/com/xiaoian/app/shell/RootShell.kt).

The rule is: **one `su` process, held open for the life of the app.** Every
`su` invocation costs a Magisk/KernelSU permission prompt, or — once granted forever — a
toast that flashes at the bottom of the screen.

Commands are framed: after each one the shell is asked to print a marker
carrying the exit status on stdout, and a matching marker on stderr, so the
streams stay apart and the reader knows where one command's output ends. The
marker carries a per-command token, so output left behind by an earlier command
can never be read as this one's.

Things worth knowing before you touch it:

- **Two shells, not one.** `RootShell.shared` is mutex-serialised and is for
  short commands. Anything that runs for minutes — a desktop start, a stop, an
  uninstall — takes `RootShell.dedicated(label)` and `NO_TIMEOUT`, so it does
  not hold the lock while the dashboard wants to run a quick query.
- **Backoff on refusal.** If a prompt is dismissed or times out, `su` exits
  immediately and *every* following command would reopen it — a flood of
  prompts. Two unanswered shells in a row put it on a 20-second cooldown. (This
  is not theoretical: fast retries once got the rule set to *deny*.)
- **Writing a script to a file beats quoting it.** Anything longer than a line
  or two goes through a file in `filesDir` that root then reads. Embedding a
  script with its own quoting inside the shell's stdin is how quoting bugs get
  in.
- **`exit` inside a command kills the shell.** Multi-line commands that want to
  bail out early wrap themselves in a subshell.

`ShellExecutor` sits on top and adds the error-priority policy: a script's
`[!]` line beats stderr, which beats the tail of stdout.

---

## Driving the scripts

`XiaoianService` is a foreground service that owns the session. It copies the
script for the chosen desktop out of the APK assets on **every** start (a copy
left by an older app version must never shadow the bundled one), installs it
under the desktop's infra root, and runs it with an environment prefix built by
[`ScriptEnv.kt`](src/app/src/main/java/com/xiaoian/app/service/ScriptEnv.kt).

The scripts discover nothing by themselves; whatever they need is passed in:

| Variable | Why |
|---|---|
| `ANLAND_BIN` | Path to `libanland.so`, the Wayland display daemon, inside `nativeLibraryDir` |
| `XIAOIAN_LIB_DIR` | Where `CmdEntryPoint` should `dlopen` `libXlorie.so` from — it runs under `app_process` and cannot ask the framework |
| `XIAOIAN_ROOTFS_TARBALL` | The app downloads it, so the app decides where it lives |

`ScriptEnv` also owns the `if (de == "kde")` decision for infra root and script
name. That ternary used to be copied at five call sites, which is how two of
them end up disagreeing.

### The line protocol

The scripts print human `[*]` and `[!]` lines *and* machine lines on stdout:

```
@@PLAN <id> <title>      every step of this run, announced up front
@@STEP <id>              this step started
@@PROGRESS <id> <done> <total>
@@DONE <id>
```

`ScriptOutputParser` turns them into `ScriptMessage`s; `SetupProgress` tracks
the plan and the current step; the service throttles notification updates to
about one a second for byte progress, while step changes always go through.
`apt` progress comes from `-o APT::Status-Fd=1`, whose `pmstatus:`/`dlstatus:`
lines are parsed the same way.

Two helpers inside the APK are started by the scripts through `app_process` and
report `@@PROGRESS` themselves when `XIAOIAN_PROGRESS_ID` is set:

- **`tools/Fetch`** — an HTTPS download using the app's own client. Downloading
  from inside the chroot is not an option: chroot hides the host paths it has to
  write to.
- **`tools/Cat`** — streams a file to stdout, and with `--xz` decompresses on
  the way. That mode exists so nothing outside the APK is needed to unpack a
  rootfs: Android's `tar` is toybox's and has no xz support at all, and the only
  xz-capable `tar` on a rooted phone is the busybox the root solution happens to
  ship. The script used to look for it at `/data/adb/magisk`, `/data/adb/ksu`
  and `/data/adb/ap` — three hard-coded paths that made the script care which
  root solution was installed. Now the app decompresses and pipes plain tar into
  the `tar` that is always there.

Both are invoked by class name from shell, so they need keep rules in
`src/app/proguard-rules.pro`. If you add a third, add the rule.

### Ending a session

There are three ways a session ends, and all of them have to converge:

| | |
|---|---|
| **Stop** button or notification | `stopSession()`: closes terminals in that chroot **first**, then runs the script's `-t`, then closes the frontend window |
| **Logout or crash inside the desktop** | The watchdog polls the state file's pid every 5 s; the desktop's own teardown has already run, so this is bookkeeping |
| **Uninstall** | Closes terminals first as well — `do_uninstall` ends in `rm -rf` |

Closing the terminals first is not tidiness. A live chroot shell makes the
script's `unmount_all()` report mounts still present and fail the stop, and an
`rm -rf` over a lazily-unmounted `/dev` bind reaches the host's device nodes.

`closeFrontend()` handles both frontends: an `ACTION_STOP` broadcast for
Termux:X11, and `MainActivity.sInstance?.finishAndRemoveTask()` for Anland. Both
activities are `singleInstance` with their own `taskAffinity`, so each desktop
gets a window of its own — `Xiaoian:Wayland` for KDE — and `finishAndRemoveTask`
rather than `finishAffinity`, or the dead task sits in recents.

---

## The terminal

A real pty with job control, VT100/xterm-256color emulation, text selection and
tabs. The emulator and view are vendored from termux-app; see
[src/terminal/README.md](src/terminal/README.md) for what was copied, from
which commit, and the exact divergences.

### Why the pty is created in two halves

`JNI.createSubprocess()` — Termux's — makes the pty *and* forks the process into
it in one step, and the forked child inherits the app's uid. Everything Xiaoian
runs in a terminal is root, so the straightforward route would mean exec'ing
`su` once per session: a Magisk prompt or toast every time, which is exactly
what `RootShell` exists to prevent.

So it is split:

1. The app opens `/dev/ptmx` with its own uid (`XiaoianPty.openPty`). An app uid
   can do this under Enforcing SELinux — measured on device, not assumed.
2. The slave's path goes to the **already open** root shell, which runs
   `libptyspawn.so` on it. That helper calls `setsid()`, opens the slave without
   `O_NOCTTY`, `TIOCSCTTY`s it and `exec`s the session script. Without
   `setsid()` + `TIOCSCTTY` there is no controlling terminal, so no job control
   and `Ctrl-C` sends nothing.
3. `TerminalSession` adopts the resulting fd and pid through a `PtyStarter`
   hook.

**Zero additional `su` invocations.** The price is that the process is no longer
the app's child, and two things follow inside `TerminalSession`:

- `waitpid()` cannot report its exit, so the waiter thread is not started and
  end-of-session comes from **EOF on the pty master** instead;
- `Os.kill()` from the app uid cannot reach a root process, so
  `finishIfRunning()` delegates to the starter.

A useful side effect: `setsid()` makes the helper a session leader, so its pid
*is* the session id. That is what teardown selects on.

### Teardown by session id

`RootPty.killSession()` walks `/proc/[0-9]*/stat`, reads field 4 after the
`comm` — the session id — and signals the matches, `TERM` then `KILL`.

- Not `kill -- -pid`: that reaches one process group, and an interactive bash
  puts each of its jobs in a group of its own.
- Not `TerminalSession.finishIfRunning()` alone: under Magisk `su` is a client
  and the real process is forked by the daemon, so a `SIGKILL` from this uid
  does not land. That is the failure mode that used to leave orphaned chroot
  processes behind and freeze the phone.
- No `awk`, no `pkill`: pure shell builtins, so it does not depend on what this
  ROM's toybox happens to include.

Selecting on the session id is also structurally incapable of touching the
desktop's own processes.

### Sessions outlive the screen

`TerminalSessions` is a process-scoped store, not Activity state. A session has
to survive rotation, and the `TerminalSessionClient` has to outlive the view
too — a session whose client went away with the Activity would NPE in
`notifyScreenUpdate()` the moment its shell wrote anything. `TerminalActivity`
only attaches one session at a time to a single `TerminalView` and reacts when
the store drops the one it is showing.

It does not survive the *process* being killed. `sweepOrphans()` records live
session ids in `filesDir` and kills the leftovers on the next start.

### Order that cannot be changed

`setTerminalViewClient()` → `setTextSize()` → `attachSession()`. `setTextSize`
builds the renderer and calls `updateSize()`, which divides by the renderer's
metrics; `attachSession` starts emulation. Either one before the client is set
is an immediate NPE. `TerminalView` also has no `(Context)` constructor — from
code it is `TerminalView(activity, null)`.

---

## Preferences

There are **three** settings screens in this APK, and that is deliberate.

| Screen | Store | Owns |
|---|---|---|
| `com.xiaoian.app.SettingsActivity` | `xiaoian` | The terminal's own options, and the shared key bar layout |
| `com.anland.termux.SettingsActivity` | `anland_settings` | The KDE/Wayland frontend |
| `com.termux.x11.LoriePreferences` | default + `secondary` | The XFCE/X11 frontend |

The app's screen links to the other two. The notification's **Preferences**
button does not: while a desktop runs it opens *that desktop's* screen
(`XiaoianService.buildNotification`), which is what you want with a session in
front of you.

### Why the two frontend screens are not merged

The question comes up because they look like near-duplicates: both have
orientation, PIP, cutout, pointer capture and a "response to user actions"
block. About 17 keys overlap by concept. They should still stay apart.

- **They are different programs.** `:lorie` drives androidx.preference from
  `res/xml/preferences.xml`, which a Gradle task (`generatePrefs`) turns into a
  typed `Prefs` class at build time, behind a `PreferenceDataStore`, with a
  second store for secondary displays and a broadcast/AIDL CLI
  (`termux-x11-preference`). `:anland` hand-builds framework `View`s — no
  androidx at all — and calls `getSharedPreferences` at each site. Neither can
  absorb the other without being rewritten.
- **The overlapping keys are not the same keys.** `hideCutout` is a boolean;
  `hide_display_cutout` is a four-value enum. `touchMode` is `"1"`/`"2"`/`"3"`;
  `touchpad_mode` is a boolean. Resolution is a mode + scale + list + custom
  quartet on one side and two integers on the other. The user-action value sets
  differ. A shared store needs a translation layer per key — more code than the
  duplication it removes, and it would bypass the `Prefs` generator that derives
  `:lorie`'s types from the XML.
- **Decisively: both modules are re-synced from upstream.** `anland-update.md`
  and `termux-x11-update.md` both say to overwrite `java/` and `res/` wholesale
  and then re-apply a short list of divergences. A merged screen would turn the
  largest and most-churned file in each module into a permanent manual merge.

### What *is* shared

The extra keys bar. The terminal draws the same `ExtraKeysBar` as the KDE
desktop, from the same `anland_settings/extra_keys_layout` JSON, so editing it
in one place changes both. XFCE is **not** in this: `:lorie` has a bar of its
own with a different format (`extra_keys_config`, Termux syntax, in the default
store), edited in its own screen.

`AppPrefs` is the single place where the borrowed store and key names are
written down — mirrored from `ExtraKeysBar.PREFS_NAME` and
`KEY_EXTRA_KEYS_LAYOUT`, so an upstream rename has one place to fix. The editor
itself only uses `ExtraKeysBar.defaultLayoutJson()` and
`ExtraKeysBar.validateLayout()`, two public statics that exist for exactly this,
which is why none of it adds a divergence to re-apply on the next sync.

One deliberate difference from anland's editor: it persists on every keystroke,
so a half-typed layout drops the bar to its built-in default mid-word. Ours
writes only when the JSON parses — or when the field is empty, which is how the
built-in layout is restored.

`TerminalActivity.onResume()` compares the stored layout against
`appliedLayoutJson` and rebuilds only on a difference, the same pull-on-resume
approach anland's `MainActivity` uses. There is no listener and no broadcast:
the settings screen is the only writer, and you always come back through
`onResume`.

---

## Mounts and mount propagation

This is the part that will bite you, so it gets its own section.

**An unmount propagates to the peers of the mount's *parent*.** Not the mount's
own peer group — its parent's. Everything below follows from that sentence.

### Why the ordinary binds are safe

`mount --bind /dev "$R/dev"` joins the host `/dev`'s peer group, which looks
alarming. It is fine: the *parent* of `$R/dev` is the `/data` mount, and the
host's `/dev` is not a child of `/data`, so unmounting it reaches nothing.

### Why `/mnt/android` is not

The Xiaoian terminal mounts the whole Android filesystem with `-o rbind /`.
`--bind` would not do — it is not recursive, so `/data`, `/system` and
`/storage` would all be missing. But the replica's root *is* a peer of the real
`/`, so unmounting its children propagates to the host's `/system`, `/data` and
the rest. Measured the hard way: the phone lost every binary mid-session and had
to be rebooted.

Two rules make it safe, and both are in
[`SessionScripts.kt`](src/app/src/main/java/com/xiaoian/app/terminal/SessionScripts.kt):

1. **Make the mount point private before mounting into it.** Bind the directory
   onto itself and take that one mount out of the peer group, so everything
   stacked on top has a private parent. Without this the ~190 submounts
   propagate into the global mount namespace as well — the app runs in its own
   namespace and `/data` is shared — and *stay* there, because rule 2 stops the
   unmount from propagating back. Measured: the other namespace sees 1 entry
   instead of 574.
2. **`mount -o rslave none <target>` immediately after the rbind.** The replica
   then takes changes from the host but sends none back, which is what makes
   tearing it down safe.

`SessionScripts.unmountTree()` is the only unmount loop in the app — `wipeRootfs`
uses it too. It goes deepest first (reverse lexicographic order gives exactly
that) and `rslave`s every mount before unmounting it.

### Internal storage, and why the desktops get only one branch

A file has one address everywhere: **`/mnt/android/storage/emulated/0`**, in the
tool rootfs and in both desktop chroots. In the tool rootfs it is simply part of
the recursive bind of `/`; the desktops bind that single directory to that same
path. `/android` in the tool rootfs is a symlink to `/mnt/android`, and
`/root/Storage` in each desktop is a symlink to the storage path, because
neither is something anyone wants to type.

Two decisions inside that are worth keeping:

**Bound from `/storage/emulated/0`, not `/data/media/0`.** The desktops used to
bind the raw path — it is faster, being FUSE's backing store rather than FUSE.
But writes there never reach MediaProvider, so nothing new appears in Gallery or
Files until a rescan, and files land as `root:root` instead of the
`<app>:media_rw` ownership Android gives its own. Through the FUSE view a file
written from inside the chroot comes out indistinguishable from one an Android
app wrote (verified on the device, including from inside the app's own mount
namespace, which is where these scripts run).

**The desktops do not get the whole `-o rbind /`.** Only the storage branch.
A full replica would mean porting the propagation-safe dance — self-bind,
`rprivate`, `rbind`, `rslave`, then `rslave` again before each `umount`,
deepest first — into both desktop scripts, whose `unmount_all` does none of
that; it walks `CHROOT_MOUNTS` and plainly unmounts each one, which for a
replica of `/` is precisely the move that once left the phone without a single
binary. It would also add ~190 mount entries per desktop session. A KDE file
manager does not need the host's `/proc`. The single non-recursive bind used
instead is exactly as safe as the `/home` bind it replaced: its parent is the
`/data` mount, so nothing propagates anywhere interesting.

It is in `CHROOT_MOUNTS`, which is what matters for the other hazard:
`do_uninstall` ends in `rm -rf`, toybox `rm` has no `--one-file-system`, and an
unmounted-but-still-present storage bind would take the walk into the phone's
own files.

**`/home` is left empty**, the way Debian has it. It used to hold the phone's
storage, which put DCIM and Download in the directory Linux reserves for user
home directories while the actual home was `/root` — two parallel sets of
`Downloads`, and a `/home/alice` would have been created inside the phone's
storage the day anyone added a user. It also leaked: `.config/Thunar` and
`.dbus/session-bus`, root-owned, were found at the top of the device's internal
storage, put there by a desktop session that resolved `~` to `/home`.

### Choosing an external display

`DisplayDetector` enumerates everything that is not `Display.DEFAULT_DISPLAY`
through `DisplayManager`, and registers a `DisplayListener` so the dashboard's
list tracks what is plugged in. The framework's logical display id is the same
number the scripts hand to `am start --display` and the same one
`dumpsys display` prints, so it can be passed straight through.

It is passed as `XIAOIAN_DISPLAY_ID` and `XIAOIAN_DISPLAY_SIZE` on the start
command (`ScriptEnv.prefix`). The size comes along because mirror mode resizes
the framework to match the external screen, and the script's own
`detect_external_res` would otherwise take the first display it finds, which
with two screens need not be the chosen one.

The scripts then **remember it for the session**, in `$INFRA_ROOT/display`
alongside the existing `mode` file. That is not redundancy: `--lock` runs as a
separate later invocation with no environment from the app, and it relaunches
the frontend on "the" external display. Without the remembered value, locking
and unlocking the phone could move the desktop to the other screen.

Order of preference in `detect_external_display_id`, each verified against
`dumpsys` before use: what the app just passed → what this session started on →
the first external display found. The verification matters because a display
can be unplugged between picking it and starting; an unchecked stale id sends
the desktop to a screen that is not there, which looks like a black window and
explains nothing.

Neither script's own detection was removed, so a start with no display passed
behaves exactly as before.

### Switching a running session's mode

`do_retarget` (`-r`/`--retarget`, same mode flags as `--start`) moves an
already-running session between local/extend/mirror without a full
stop/start: the "close the compositor window, reopen it on the target
display" trick that `LorieApp`'s close/reopen fix (see "Things that are not
what they look like") already made safe to do quickly.

It is not a free move between any two modes. Extend and local share one set
of `settings put global` values (`enable_freeform_support` and friends);
mirror needs a different set, and those only take effect **after a reboot** —
the same rule `do_start` already enforces. `do_retarget` runs the identical
check first, before touching anything: if the currently-active settings
do not match the target mode, it refuses and leaves the session exactly as
it was, with the same "reboot, then start normally in that mode" message
`do_start` gives. There is no way around this by going through local as a
stopover — local does not touch the settings either way, so passing through
it changes nothing about whether extend and mirror agree.

Ownership is split on purpose: the script owns the settings check, the
`wm size`/`density` swap (only mirror ever needs it, via the existing
`apply_external_size`/`restore_internal_size`), the mode file, and the fresh
`am start` on success. Closing the *previous* frontend instance is the
caller's job (`XiaoianService.retargetSession`, via the existing
`closeFrontend()`) — Anland has no shell-reachable close path, only the
static Kotlin instance can finish it, so that half could never live in the
script anyway. The service only closes the old frontend and updates its own
state after the script has already succeeded, so a refused retarget never
leaves the visible desktop worse off than before it was asked to move.

### Never ask `/proc/mounts` whether something is mounted

Not for anything under the app's data directory, anyway. `context.filesDir` is
`/data/user/0/<pkg>/files`, but inside an app's mount namespace `/data/user/0`
is reached through a bind mount, so the kernel records mount points under its
own spelling — `/data/data/<pkg>/files/rootfs/proc`. Both paths exist, both are
real directories (neither is a symlink, so `getCanonicalPath()` and
`readlink -f` are no help), and `mount`/`umount` accept either. Only
`/proc/mounts` insists on one.

`grep " $target " /proc/mounts` therefore never matched, and it was doing three
jobs:

| | Consequence |
|---|---|
| `ensure_mount`'s "already there?" check | Four `could not mount` lines on every Xiaoian terminal, after everything had mounted fine |
| the `/mnt/android` guard | Every session re-bound the whole Android tree — two full copies measured on the device |
| `unmountTree` | Found nothing, so `releaseAndroidBind` silently never released anything |

The first two now use `is_mountpoint`, which compares a directory's device
number against its parent's (`stat -c %d`) and so asks the kernel rather than
the path. It cannot see a bind of a directory onto another directory of the
same filesystem, which is fine here: every mount involved brings its own
filesystem.

`unmountTree` still has to work by path — it enumerates submounts whose names
it cannot know — so it matches **both** spellings, translating `/data/user/0/…`
to `/data/data/…` and back.

### A chroot terminal without its desktop

If the desktop is not running, the session mounts the same set as the Xiaoian terminal
(`proc`, `sys`, `dev`, `dev/pts` and internal storage) — all of them in
`CHROOT_MOUNTS`, so a desktop started later finds them (`is_mounted || mount`)
and its stop still tears them down. `run`, `tmp` and `dev/shm` are left alone on
purpose: the script mounts `run` as a *fresh* tmpfs per session and only clears
the stale one while it is unmounted.

Starting the desktop afterwards must not kill that shell. `do_start` begins by
clearing whatever it finds in the chroot (`unmount_all`, which calls
`stop_chroot_procs` first), and that killed the terminal and unmounted from
under it. So the app passes `XIAOIAN_OPEN_SESSIONS` — the path of
`TerminalSessions.openListFile()`, one live session id per line — and the script
splits the chroot's processes with `chroot_pids_owned_by app|foreign`, comparing
each one's session id (field 4 after the comm in `/proc/N/stat`, the same field
`RootPty` and `sweepOrphans` use, and the reason `ptyspawn` calls `setsid()`).
When the only things in the chroot are in-app terminals it skips the teardown
entirely; `mount_all` is idempotent, so a chroot that is already set up needs
nothing doing to it.

Only the **start** path spares them. Teardown still kills everything: by then
`stopSession` has already run `closeAllFor`, so the list is empty, and anything
left would keep the mounts busy and fail the stop.

---

## What the scripts assume about the hardware

Four places in both scripts look at what the phone actually is, rather than
what the test phone happens to be:

- **The touchscreen**, for the virtual lock. `find_touch_inhibit` takes the
  device named `fts_ts` (this phone's), and otherwise the one `getevent -lp`
  says reports `ABS_MT_POSITION_X`. It used to fall back to a hardcoded
  `/sys/class/input/input5`, which on this phone is right by luck — `input3` is
  `gpio-keys`, and inhibiting that would disable the power button too, leaving
  the lock with no way out. Without a match the lock still runs, minus the
  touch part: `inhibited` is a 5.11 kernel feature.
- **The backlight**: `/sys/class/backlight/*/brightness`, then
  `/sys/class/leds/*backlight*/brightness`, which is where MediaTek puts it.
- **The GPU**: both scripts gate the Freedreno/Turnip environment on
  `/dev/kgsl-3d0`. The driver tarball is installed on every device, so its
  presence proves nothing. Without an Adreno, XFCE says so and runs llvmpipe,
  and KDE warns that it is not supported — Mesa's Mali drivers want the
  mainline kernel driver, which no stock Android phone ships. Where the driver
  comes from is in [The chroot's Mesa driver](#the-chroots-mesa-driver).
- **`enable_non_resizable_multi_window`** only exists from Android 12. Below
  that it is left out of the comparison instead of being read as 0, which
  would have asked for a reboot that could not change anything.

### The chroot's Mesa driver

The desktops render through a Mesa build the scripts download from this
repository's latest release and unpack over the Debian rootfs: a plain `/usr`
tree, not `.deb` packages. It is built by the `mesa` job of
[release.yml](.github/workflows/release.yml) from [mesa/](mesa/):
`version.txt` pins the source, `build.sh` builds and packages it.

- **It cannot be part of the Gradle build.** The NDK builds against bionic; the
  chroot is Debian, glibc. The job runs natively on GitHub's arm64 runner in a
  `debian:trixie` container.
- **The source is lfdevs' fork, not upstream Mesa.** Turnip talks to KGSL
  upstream too (`-Dfreedreno-kmds=kgsl`), which is all XFCE needs: it renders
  through zink on Turnip. KDE runs on the *gallium* freedreno driver over KGSL
  (`GALLIUM_DRIVER=freedreno`, the `kgsl` loader), and upstream's gallium
  driver has no KGSL backend — `src/freedreno/drm/` has only `msm` and
  `virtio`. The pin is the commit lfdevs' own `mesa-26.3.0-devel-20260824`
  release came from, so the package matches what the scripts downloaded
  before.
- **It is only rebuilt when `mesa/` changes.** The finished tarball is cached
  under the hash of that directory; a release that does not touch it reuses
  the package in seconds. Its name carries the Mesa version and the pinned
  commit but no date, so a phone that has it does not download it again.
- **Panfrost is not in it.** On a stock Android kernel PanVK needs the
  out-of-tree `mali_kbase` patches, one set per GPU generation, and none of it
  can be tested without a Mali phone.

The scripts still accept lfdevs' old file name, so a phone with only that one
cached keeps working offline until it can download the new one.

---

## Things that are not what they look like

- **`plan/`** is a set of early design notes. Parts are superseded and at least
  one claim in it was wrong (the licensing); it is kept for history, not as a
  specification.
- **The log viewer reads through the root shell.** The logs are in the
  desktops' infra roots under `/data/local`, owned by root, so
  `logs/DesktopLogs` lists them (by glob, `*.log` and `*.log.N`) and reads them
  over `RootShell.shared` like any other short command — only the last 512 KB,
  since a session log can grow without bound.
- **`XiaoianService.ACTION_TERMINAL`** is handled but no notification button
  sends it.
- **`XiaoianApplication` extends `LorieApp`**, not `Application` — Termux:X11's
  application class has to run for the X11 frontend to work.
- **`nonTransitiveRClass=true`** is set project-wide, which is why `:terminal`
  has to be namespaced `com.termux.view`: a library's R class is
  `<namespace>.R`, and the vendored text-selection code does
  `import com.termux.view.R`.

---

## Verifying a change on the device

The parts that are hard to get right are hard to see, so measure rather than
assume:

```sh
adb logcat -s RootShell:*          # one "root shell opened" per app lifetime, not per command
adb shell su -c 'grep -c rootfs /proc/mounts'
adb shell su -c 'readlink /proc/$(pidof com.xiaoian.app)/ns/mnt'   # the app's own namespace
adb shell su -c 'grep " <path>" /proc/self/mountinfo'              # shared: vs master:
```

A `shared:` tag on anything you are about to unmount under a replica of `/` is
the warning sign. And after any change to the mount code, check the host is
still intact — `ls /system`, `ls /dev/pts` — before trusting the result.

Worth running before a release: `:app:assembleRelease` with minify and
`shrinkResources` on, then actually installing it. R8 can break JNI binding and
reflective entry points in ways debug builds never show.
