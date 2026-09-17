# Xiaoian Android App — Implementation Plan

> **Goal:** A single Android APK that replaces Termux + Termux:X11 + Anland.Termux — providing a full Linux desktop on an external display with zero external dependencies.

## Document Index

| Document | Description |
|---|---|
| [01-architecture.md](01-architecture.md) | High-level architecture, component diagram, data flow |
| [02-phases.md](02-phases.md) | Phased rollout plan with milestones and timelines |
| [03-terminal-emulator.md](03-terminal-emulator.md) | Terminal emulator integration details |
| [04-bootstrap-environment.md](04-bootstrap-environment.md) | Termux bootstrap / $PREFIX environment setup |
| [05-display-renderers.md](05-display-renderers.md) | X11 and Wayland/Anland renderer integration |
| [06-session-management.md](06-session-management.md) | Android Service, notification, lifecycle management |
| [07-ui-design.md](07-ui-design.md) | UI screens, navigation, user flows |
| [08-shell-script-changes.md](08-shell-script-changes.md) | Required changes to the existing .sh scripts |
| [09-risks-and-mitigations.md](09-risks-and-mitigations.md) | Technical risks, licensing, and mitigations |
| [10-tech-stack.md](10-tech-stack.md) | Technology choices, dependencies, build system |

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

## License

The final app will be **GPL-3.0** (required by Termux GPL-3.0 and Termux:X11 GPL-2.0 code usage).
