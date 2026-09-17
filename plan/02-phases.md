# 02 — Phased Rollout Plan

## Overview

```
Phase 1          Phase 2              Phase 3              Phase 3b
"Smart Wrapper"  "Built-in Renderer"  "Zero Dependency"    "Chroot Terminal"
   4 weeks           4 weeks              8 weeks              3 days
     │                  │                    │                    │
     ▼                  ▼                    ▼                    ▼
┌─────────┐      ┌───────────┐        ┌───────────┐       ┌───────────┐
│ Xiaoian │      │ Xiaoian   │        │ Xiaoian   │       │ Xiaoian   │
│   +     │      │   +       │        │           │       │           │
│ Termux  │      │ Termux    │        │ (alone)   │       │ (alone)   │
│   +     │  ──► │           │  ────► │           │  ──►  │           │
│ Anland  │      │           │        │           │       │           │
│   +     │      │           │        │           │       │           │
│ T:X11   │      │           │        │           │       │           │
└─────────┘      └───────────┘        └───────────┘       └───────────┘
 4 apps           2 apps               1 app ✨            1 app ✨
```

---

## Phase 1: Smart Wrapper (4 weeks)

**Goal:** A beautiful Material 3 control panel that orchestrates the existing setup. Still requires Termux + Anland/Termux:X11 as separate apps.

**Why start here:** Delivers immediate user value with minimal risk. Validates the UI/UX before deeper integration.

### Week 1: Project Setup & Core Service

| Task | Details |
|---|---|
| Project scaffolding | Android Studio, Kotlin, Compose, Gradle (minSdk 28, targetSdk 35) |
| `ShellExecutor` | `su -c` wrapper with stdout/stderr streaming, timeout, cancellation |
| `XiaoianService` | Foreground Service with persistent notification |
| `SessionManager` | State machine: `IDLE → INSTALLING → STARTING → RUNNING → STOPPING` |
| Root detection | Check for `su` binary, show friendly error if not rooted |

### Week 2: Main UI

| Task | Details |
|---|---|
| Dashboard screen | DE selector (KDE/XFCE), mode picker (extend/mirror/local), start/stop buttons |
| Status display | Current state, session PID, uptime, active mode |
| Display detection | `DisplayManager.getDisplays()` — show connected external displays |
| Settings screen | Auto-start on boot, default DE, default mode, infra root path |

### Week 3: Notification & Lifecycle

| Task | Details |
|---|---|
| Notification actions | Stop, Lock, Unlock action buttons in persistent notification |
| `BootReceiver` | `BOOT_COMPLETED` intent → optional auto-start |
| Script output parser | Parse `[*]` (info), `[!]` (warning/error) prefixes → UI progress |
| Log viewer | Read `de_debug.log`, show in-app with search and auto-scroll |

### Week 4: Polish & Testing

| Task | Details |
|---|---|
| Error handling | Network errors, root denied, missing apps, script failures |
| First-run wizard | Check prerequisites (root, Termux, Anland/X11), guide user to install missing ones |
| Edge cases | Session crash recovery, reboot handling, multiple instances |
| Testing | Manual testing on at least 2 devices |

### Phase 1 Deliverable
- APK that can start/stop/lock the desktop via a GUI
- Persistent notification with action buttons
- Log viewer
- Still requires: Termux, Anland.Termux (for KDE), Termux:X11 (for XFCE)

---

## Phase 2: Built-in Display Renderers (4 weeks)

**Goal:** Eliminate the Anland.Termux and Termux:X11 Android apps. The Xiaoian app renders the desktop directly.

**Prerequisite:** Confirm Anland.Termux source code availability and license.

### Week 5: X11 Renderer Integration

| Task | Details |
|---|---|
| Fork Termux:X11 renderer | Extract `TermuxX11View`, `InputEventHandler`, JNI rendering code |
| Create `X11SurfaceView` | Embed the renderer as a custom View in the Xiaoian app |
| `DisplayActivity` | Full-screen Activity with the renderer, launched via `am start --display` |
| Socket connection | Connect to `/tmp/.X11-unix/X0` (same socket the standalone app uses) |
| Input forwarding | Touch → pointer motion/clicks, Android keyboard → X11 keycodes |

### Week 6: Anland/Wayland Renderer Integration

| Task | Details |
|---|---|
| Fork Anland.Termux renderer | Extract Surface rendering, Wayland frame receiver, audio forwarding |
| Create `AnlandSurfaceView` | Embed as custom View alongside X11SurfaceView |
| Protocol handling | Connect to `$TMPDIR/anland/display_daemon.sock` |
| Audio bridge | Preserve Anland's speaker output and mic input forwarding |
| Mode switching | `DisplayActivity` reads intent extra `display_mode` to pick renderer |

### Week 7: Shell Script Adaptation

| Task | Details |
|---|---|
| Modify `am start` targets | Change from `com.anland.termux` / `com.termux.x11` → `com.xiaoian.app` |
| Intent extras | Pass `display_mode=wayland` or `display_mode=x11` |
| Backward compatibility | Scripts detect if launched standalone (old behavior) or from app |
| Test both DE paths | KDE on Anland renderer, XFCE on X11 renderer, all 3 modes |

### Week 8: Integration Testing

| Task | Details |
|---|---|
| External display lifecycle | Plug/unplug during session, display sleep/wake |
| Resolution handling | Auto-detect and apply resolution for all modes |
| Input edge cases | Multi-touch, keyboard layouts (HU!), special keys |
| Performance profiling | Frame rate, input latency, memory usage comparison vs standalone apps |

### Phase 2 Deliverable
- Xiaoian app renders the desktop directly (no Anland.Termux or Termux:X11 app needed)
- Still requires: Termux (for shell environment, anland daemon, termux-x11 server)
- **User installs: 2 apps** (Termux + Xiaoian)

---

## Phase 3: Zero External Dependencies (8 weeks)

**Goal:** Eliminate the Termux app. The Xiaoian app bootstraps its own Linux environment.

### Week 9–10: Bootstrap Manager

| Task | Details |
|---|---|
| `BootstrapManager` class | Downloads and extracts Termux bootstrap tarball |
| Bootstrap source | Use official Termux bootstrap from `https://packages.termux.dev/bootstrap/` |
| First-run flow | Splash screen → "Setting up environment..." → progress bar |
| Directory structure | Create `$PREFIX` under `/data/data/com.xiaoian.app/files/usr/` |
| Environment variables | `PREFIX`, `PATH`, `LD_LIBRARY_PATH`, `TMPDIR`, `HOME`, `ANDROID_ROOT` |

### Week 11–12: Package Management

| Task | Details |
|---|---|
| APT configuration | Configure `sources.list` for Termux repos |
| Required packages | Auto-install: `anland`, `termux-x11-nightly`, `pulseaudio`, `wget`, `tar` |
| Package state tracking | Track what's installed, offer updates |
| Offline support | After first setup, no internet needed for normal operation |

### Week 13–14: Terminal Emulator

| Task | Details |
|---|---|
| Fork `termux-view` | Terminal rendering library (VT100/xterm-256color) |
| `TerminalView` widget | Scrollable, selectable, with toolbar |
| PTY management | Create pseudo-terminal, fork process, handle I/O |
| Session types | "Local" (bash in $PREFIX), "Chroot" (bash in Debian), "Root" (su shell) |
| Keyboard | Extra keys row (Ctrl, Alt, Tab, Esc, arrows), hardware keyboard support |
| Multiple sessions | Tab bar for multiple terminal sessions |

### Week 15–16: Shell Script Adaptation & Testing

| Task | Details |
|---|---|
| `$PREFIX` path changes | Scripts must use the Xiaoian app's `$PREFIX` instead of Termux's |
| `TERMUX_UID` handling | The app's UID replaces Termux's UID in the scripts |
| SELinux testing | Verify all operations work under the app's SELinux context with root |
| End-to-end testing | Fresh install → bootstrap → rootfs → desktop → stop → uninstall |
| Crash recovery | Bootstrap interrupted, download failed, partial extraction |
| Update mechanism | Bootstrap update, package updates, rootfs updates |

### Phase 3 Deliverable
- **Single APK, zero dependencies**
- Built-in terminal emulator with local and chroot sessions
- Built-in display renderer (X11 + Wayland)
- Built-in bootstrap environment (Termux-compatible $PREFIX)
- **User installs: 1 app** ✨

---

## Phase 3b: Chroot Terminal Enhancement (3 days)

**Goal:** Add a seamless chroot terminal experience.

| Task | Details | Time |
|---|---|---|
| "Open in Chroot" button | Launches terminal directly into `chroot .../debian /bin/bash -l` | 4h |
| Auto-detect chroot | If Debian rootfs exists, show "Chroot" tab in terminal | 2h |
| Environment setup | Proper `HOME`, `PATH`, `LANG`, `TERM` for the chroot shell | 2h |
| Visual indicator | Different terminal color scheme for chroot vs local sessions | 2h |
| Notification action | "Terminal" button in notification → opens chroot terminal | 2h |

---

## Timeline Summary

```
Month 1              Month 2              Month 3              Month 4
┌────────────────┐   ┌────────────────┐   ┌────────────────┐   ┌───────────────┐
│ Phase 1        │   │ Phase 2        │   │ Phase 3        │   │ Phase 3 cont. │
│ Smart Wrapper  │   │ Renderers      │   │ Bootstrap +    │   │ Testing +     │
│                │   │                │   │ Terminal       │   │ Phase 3b      │
│ Result:        │   │ Result:        │   │                │   │ Result:       │
│ 4 apps         │   │ 2 apps         │   │                │   │ 1 app ✨      │
└────────────────┘   └────────────────┘   └────────────────┘   └───────────────┘
```

## Release Strategy

| Version | Phase | What ships |
|---|---|---|
| **v0.1-alpha** | Phase 1 done | Control panel only (needs Termux + display apps) |
| **v0.5-beta** | Phase 2 done | Built-in renderers (needs Termux only) |
| **v1.0** | Phase 3 done | Standalone app, zero dependencies |
| **v1.1** | Phase 3b | Chroot terminal, polish |
