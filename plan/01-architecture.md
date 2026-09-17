# 01 — Architecture

## High-Level Component Diagram

```
┌─────────────────────────────────────────────────────────────────────┐
│                        com.xiaoian.app                              │
│                                                                     │
│  ┌─ UI Layer (Jetpack Compose) ──────────────────────────────────┐ │
│  │                                                                │ │
│  │  MainActivity              DisplayActivity     TerminalActivity│ │
│  │  ┌──────────────┐         ┌───────────────┐   ┌─────────────┐ │ │
│  │  │ Dashboard    │         │ Surface       │   │ Terminal    │ │ │
│  │  │ • DE selector│         │ Renderer      │   │ Emulator   │ │ │
│  │  │ • Mode picker│  ────►  │ (X11/Wayland) │   │ (local /  │ │ │
│  │  │ • Start/Stop │         │               │   │  chroot)  │ │ │
│  │  │ • Status     │         │ Input Handler │   │           │ │ │
│  │  │ • Log viewer │         │ (touch→mouse) │   │           │ │ │
│  │  └──────────────┘         └───────────────┘   └─────────────┘ │ │
│  └────────────────────────────────────────────────────────────────┘ │
│                                                                     │
│  ┌─ Service Layer ───────────────────────────────────────────────┐ │
│  │                                                                │ │
│  │  XiaoianService (Foreground Service)                          │ │
│  │  ┌──────────────────────────────────────────────────────────┐ │ │
│  │  │ SessionManager     │ ShellExecutor    │ DisplayDetector  │ │ │
│  │  │ • state machine    │ • su -c wrapper  │ • DisplayManager │ │ │
│  │  │ • lifecycle        │ • script runner  │ • MediaRouter    │ │ │
│  │  │ • watchdog         │ • output parser  │ • auto-detect    │ │ │
│  │  └──────────────────────────────────────────────────────────┘ │ │
│  │                                                                │ │
│  │  BootReceiver          NotificationManager                    │ │
│  │  • BOOT_COMPLETED      • persistent notification              │ │
│  │  • auto-start option   • action buttons (Stop/Lock/Terminal)  │ │
│  └────────────────────────────────────────────────────────────────┘ │
│                                                                     │
│  ┌─ Environment Layer ───────────────────────────────────────────┐ │
│  │                                                                │ │
│  │  /data/data/com.xiaoian.app/files/                            │ │
│  │  ├── usr/              ← Termux-compatible $PREFIX            │ │
│  │  │   ├── bin/          (bash, wget, tar, anland, termux-x11)  │ │
│  │  │   ├── lib/          (shared libraries)                     │ │
│  │  │   ├── etc/          (apt sources, configs)                 │ │
│  │  │   └── var/          (dpkg database, apt cache)             │ │
│  │  └── home/             ← Termux $HOME equivalent             │ │
│  │                                                                │ │
│  │  BootstrapManager                                             │ │
│  │  • first-run bootstrap download & extraction                  │ │
│  │  • package installation (anland, termux-x11, pulseaudio)      │ │
│  │  • environment validation                                     │ │
│  └────────────────────────────────────────────────────────────────┘ │
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘
          │                           │                    │
          │ su -c ./xiaoian-*.sh      │ unix sockets       │ PTY
          ▼                           ▼                    ▼
┌──────────────────┐  ┌───────────────────────┐  ┌─────────────────┐
│ Shell Scripts    │  │ Display Servers       │  │ Shell Sessions  │
│ (unchanged)      │  │ (Termux packages)     │  │                 │
│                  │  │                       │  │ • local bash    │
│ xiaoian-wayland  │  │ anland daemon         │  │ • chroot bash   │
│ xiaoian-x11      │  │ termux-x11 :0         │  │                 │
│                  │  │ pulseaudio            │  │                 │
└────────┬─────────┘  └───────────────────────┘  └─────────────────┘
         │
         ▼
┌──────────────────────────────────────────────────────────────────┐
│ /data/local/xiaoian-wayland-kde/debian/    (or xiaoian-x11-xfce)│
│                                                                  │
│ Debian Trixie arm64 chroot                                       │
│ ├── KDE Plasma 6 + KWin Anland backend     (Wayland variant)    │
│ └── XFCE 4 + xfce4-session                 (X11 variant)        │
└──────────────────────────────────────────────────────────────────┘
```

## Data Flow — Start Sequence

```
User taps "Start"
    │
    ▼
MainActivity → XiaoianService.startSession(mode, de)
    │
    ├─ 1. BootstrapManager.ensureReady()
    │     └─ Is $PREFIX/bin/bash present? → if not: download + extract bootstrap
    │
    ├─ 2. DisplayDetector.findExternalDisplay()
    │     └─ DisplayManager API → displayId (or null for local mode)
    │
    ├─ 3. SessionManager.start()
    │     ├─ ShellExecutor.run("su -c './xiaoian-wayland-kde.sh -s --extend'")
    │     ├─ Parse stdout for progress messages ([*] ... / [!] ...)
    │     ├─ Update UI state (installing / starting / running / error)
    │     └─ Wait for session confirmation
    │
    ├─ 4. Launch DisplayActivity on target display
    │     ├─ am start --display <id> (for extend mode)
    │     └─ DisplayActivity picks renderer (X11SurfaceView / AnlandSurfaceView)
    │
    └─ 5. Update notification (persistent, with Stop/Lock/Terminal actions)
```

## Data Flow — Display Rendering

```
┌─ Debian chroot ──────────┐
│                           │
│  KDE Plasma / XFCE        │
│  (renders to Wayland/X11) │
│           │                │
└───────────┼────────────────┘
            │ Wayland protocol / X11 protocol
            ▼
┌─ Display Server ─────────┐
│                           │
│  anland daemon            │  ← Wayland: creates display_daemon.sock
│  termux-x11 :0            │  ← X11: creates /tmp/.X11-unix/X0
│           │                │
└───────────┼────────────────┘
            │ unix socket (shared memory / frame data)
            ▼
┌─ Xiaoian App ────────────┐
│                           │
│  DisplayActivity          │
│  ├─ AnlandSurfaceView     │  ← reads from display_daemon.sock
│  │  or                    │
│  ├─ X11SurfaceView        │  ← reads from X0 socket
│  │                        │
│  └─ InputHandler          │  ← touch events → pointer/keyboard events
│     (sends back to server)│     via the same socket
│                           │
└───────────────────────────┘
```

## Data Flow — Terminal

```
┌─ Xiaoian App ────────────────────────┐
│                                       │
│  TerminalActivity                     │
│  ┌─ TerminalView ──────────────────┐ │
│  │  VT100/xterm-256color emulation │ │
│  │  Text grid rendering            │ │
│  │  Keyboard input handling        │ │
│  └──────────┬──────────────────────┘ │
│             │ PTY (read/write)        │
│             ▼                         │
│  ┌─ Process ─────────────────────┐   │
│  │                                │   │
│  │  Mode A: "Local"              │   │
│  │  exec bash -l                  │   │
│  │  env: PREFIX, PATH, TMPDIR     │   │
│  │                                │   │
│  │  Mode B: "Chroot"             │   │
│  │  exec su -c 'chroot            │   │
│  │    /data/local/.../debian      │   │
│  │    /usr/bin/env -i             │   │
│  │    HOME=/root                  │   │
│  │    PATH=/usr/bin:/bin          │   │
│  │    TERM=xterm-256color         │   │
│  │    /bin/bash -l'               │   │
│  └────────────────────────────────┘   │
│                                       │
└───────────────────────────────────────┘
```

## Key Design Decisions

### 1. Shell scripts remain the engine
The existing `.sh` scripts contain ~3000 lines of battle-tested logic for chroot management, package installation, mount handling, GPU setup, audio configuration, and session lifecycle. Rewriting this in Kotlin would be:
- Months of effort
- A new source of bugs
- Harder to debug (no `cat de_debug.log`)

The app calls the scripts via `su -c` and parses their stdout/stderr for status updates.

### 2. Termux $PREFIX is replicated, not shared
The app maintains its own `$PREFIX` under `/data/data/com.xiaoian.app/files/usr/`. This is identical in structure to Termux's, uses the same bootstrap tarballs, and the same apt repositories. This avoids:
- Depending on Termux being installed
- SELinux cross-app data access issues
- Version conflicts

### 3. Display rendering is embedded
The Anland and Termux:X11 Android app source code is forked and integrated as Views within the Xiaoian app. The display servers (anland daemon, termux-x11 process) still run as separate processes in the $PREFIX environment — only the rendering frontend is embedded.

### 4. Single Activity per concern
- `MainActivity` — dashboard and control panel
- `DisplayActivity` — full-screen desktop rendering (launched on target display)
- `TerminalActivity` — terminal emulator (can be launched from notification)

This allows `DisplayActivity` to be launched on an external display via `am start --display` while `MainActivity` stays on the phone.
