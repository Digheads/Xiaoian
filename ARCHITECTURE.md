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
cd src
./gradlew.bat :app:assembleDebug          # or :app:assembleRelease
../tools/platform-tools/adb.exe install -r app/build/outputs/apk/debug/app-debug.apk
```

Java 21 (set in `src/gradle.properties`), compileSdk 35, minSdk 28, arm64 only.

### Native code is built by hand

**No module has an `externalNativeBuild` block.** The `.so` files under each
module's `src/main/jniLibs/arm64-v8a/` are prebuilts, and each module that has
native sources carries a script that regenerates them:

```sh
sh src/anland/build-natives.sh      # libanland_consumer.so, libanland.so, libfdhelper.so
sh src/terminal/build-natives.sh    # libtermux.so, libptyspawn.so
```

Run the script after touching anything under that module's `src/main/jni/`.
Nothing else will: a prebuilt that has drifted from the source beside it
compiles and installs perfectly happily and then crashes on first use, which is
exactly what `libanland_consumer.so` once did — it was missing a symbol that its
Java side declared. Both scripts end by checking the expected JNI symbols with
`llvm-nm`, which is the only thing standing between you and that failure mode.

API levels are not interchangeable: the Anland consumer must be built at **API
30** because it calls `memfd_create()`, which bionic only declares from 30 on.
The terminal natives are built at 28 to match `minSdk`.

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
| Termux:X11 (`:lorie`) | [termux-x11-update.md](termux-x11-update.md) | Java from source, native `.so` lifted out of the official release APK |
| Anland (`:anland`) | [anland-update.md](anland-update.md) | Same: Java from source, native `.so` from the release APK |
| Termux terminal (`:terminal`) | [terminal-update.md](terminal-update.md) | Fully vendored — Java *and* the native source, built here |

The first two exist because their C/C++ builds want a full NDK/CMake but I don't;
so taking the prebuilt `.so` out of the matching release APK sidesteps
that entirely. **The APK and the source drop must be the exact same version**,
or the Java side will call into a library that does not have the symbol.

`:terminal` is different: its native side is one small C file, so it is built
here from the copied source, and `src/terminal/build-natives.sh` produces it.
The guide is mostly about re-applying the four local divergences and re-running
the symbol check.

Two things that apply whichever guide you are following:

- **The `.so` files under `jniLibs/` are prebuilts.** Nothing in the Gradle
  build regenerates them, so a file that has drifted from the source beside it
  builds and installs happily and crashes on first use. If you change in-tree
  native sources, run that module's `build-natives.sh`; if you replace the
  binaries from an APK, check that the Java side's `native` declarations still
  match what the new library exports.
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

## Mounts and mount propagation

This is the part that will bite you, so it gets its own section.

**An unmount propagates to the peers of the mount's *parent*.** Not the mount's
own peer group — its parent's. Everything below follows from that sentence.

### Why the ordinary binds are safe

`mount --bind /dev "$R/dev"` joins the host `/dev`'s peer group, which looks
alarming. It is fine: the *parent* of `$R/dev` is the `/data` mount, and the
host's `/dev` is not a child of `/data`, so unmounting it reaches nothing.

### Why `/mnt/android` is not

The local terminal mounts the whole Android filesystem with `-o rbind /`.
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

`/mnt/android` is deliberately **not** offered in the desktop chroots: it is
outside the scripts' `CHROOT_MOUNTS`, so `do_uninstall` would not unmount it
before its `rm -rf`, and that `rm -rf` would walk into the host filesystem.
toybox `rm` has no `--one-file-system`.

### A chroot terminal without its desktop

If the desktop is not running, the session mounts the same four as the local one
(`proc`, `sys`, `dev`, `dev/pts`) — all of them in `CHROOT_MOUNTS`, so a desktop
started later finds them (`is_mounted || mount`) and its stop still tears them
down. `run`, `tmp`, `home` and `dev/shm` are left alone on purpose: the script
mounts `run` as a *fresh* tmpfs per session and only clears the stale one while
it is unmounted.

---

## Things that are not what they look like

- **`plan/`** is a set of early design notes. Parts are superseded and at least
  one claim in it was wrong (the licensing); it is kept for history, not as a
  specification.
- **`ui/screens/LogViewerScreen.kt`, `ui/screens/SettingsScreen.kt` and
  `display/DisplayDetector.kt`** are not referenced from anywhere. Dead, or not
  wired up yet.
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
