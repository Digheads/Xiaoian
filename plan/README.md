# Xiaoian Android App — Implementation Plan

> **Goal:** A single Android APK that replaces Termux + Termux:X11 + Anland.Termux — providing a full Linux desktop on an external display with zero external dependencies.

## Document Index

| Document | Description |
|---|---|
| [01-architecture.md](01-architecture.md) | High-level architecture, component diagram, data flow |
| [02-phases.md](02-phases.md) | Phased rollout plan with milestones and timelines |
| [03-terminal-emulator.md](03-terminal-emulator.md) | Terminal emulator integration details |
| [04-bootstrap-environment.md](04-bootstrap-environment.md) | Built-in Debian environment: tool rootfs, rootfs tarball, package install |
| [05-display-renderers.md](05-display-renderers.md) | X11 and Wayland/Anland renderer integration |
| [06-session-management.md](06-session-management.md) | Android Service, notification, lifecycle management |
| [07-ui-design.md](07-ui-design.md) | UI screens, navigation, user flows |
| [08-shell-script-changes.md](08-shell-script-changes.md) | Required changes to the existing .sh scripts |
| [09-risks-and-mitigations.md](09-risks-and-mitigations.md) | Technical risks, licensing, and mitigations |
| [10-tech-stack.md](10-tech-stack.md) | Technology choices, dependencies, build system |

### Component update guides

| Document | Description |
|---|---|
| [update/termux-x11-update.md](update/termux-x11-update.md) | Refreshing the embedded Termux:X11 engine (`lorie` module) |
| [update/anland-update.md](update/anland-update.md) | Refreshing the embedded Anland engine (`anland` module) |

## Summary

### What the user sees today

```
Install Termux → Install Termux:X11 → Install Anland.Termux
  → Open Termux → su → run shell script → wait → switch to Anland/X11 app
```

### What the user will see

```
Install Xiaoian → Open → Tap "Install" → Tap "Start" → Desktop appears
```

### Core principles

1. **The shell scripts remain the engine** — proven, stable, no rewrite
2. **The app is an orchestrator + renderer** — not a replacement for the scripts
3. **Progressive enhancement** — each phase delivers a usable product
4. **Offline-first** — after initial setup, everything works without internet
5. **Single APK** — no external app dependencies

## Status (2026-09-18)

The rollout order changed: the **X11 renderer and X server were pulled into Phase 1**. The app runs the scripts with plain `su -c`, outside any Termux environment, so a Termux-installed `termux-x11` server had nothing to connect to. The Termux:X11 frontend now lives in the `lorie` module, and the X server is started from the APK itself (`app_process … com.termux.x11.CmdEntryPoint`).

| Area | State |
|---|---|
| XFCE / X11 | Runs without Termux. Sound now comes from PulseAudio inside the chroot instead of Termux |
| KDE / Wayland | No longer depends on Termux either: the `anland` display daemon ships in the APK as `libanland.so` and the script starts it directly (script 2.10.0) |
| Environment | No Termux bootstrap. A Debian tool rootfs under `files/rootfs` supplies `wget`/`tar`; both desktops extract their chroot from one shared tarball in `files/downloads/` |
| Scripts | `app/src/main/assets/` = app variants; repo root = standalone Termux **reference** scripts (not modified) |

> **Open blocker:** the scripts reach `wget`/`tar` through `chroot files/rootfs …`, so those tools cannot see host paths — neither the tarball nor the destination chroot. See [04 § Reaching host paths](04-bootstrap-environment.md#open-problem-reaching-host-paths-from-the-tool-rootfs) and [09 R14](09-risks-and-mitigations.md#r14-tool-rootfs-cannot-see-host-paths).

Details: [02-phases.md](02-phases.md#phase-1-smart-wrapper-4-weeks), [05-display-renderers.md](05-display-renderers.md#x11-renderer-termuxx11-fork), [08-shell-script-changes.md](08-shell-script-changes.md#implemented-xfce-script-160), [09-risks-and-mitigations.md](09-risks-and-mitigations.md#r10-termux-packages-have-a-hardcoded-prefix).

## License

The final app will be **GPL-3.0** (required by Termux GPL-3.0 and Termux:X11 GPL-2.0 code usage).
