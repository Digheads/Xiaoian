# 07 — UI Design

## Design Language

- **Material 3** (Material You) with dynamic color theming
- **Dark mode by default** (desktop Linux aesthetic)
- Color palette: deep blue-grey background, cyan/teal accents
- Typography: **Inter** or **Outfit** (Google Fonts)
- Smooth transitions between states

## Screen Map

```
┌─────────────────────────────────────────────────────┐
│                                                     │
│  SplashScreen (first run only)                      │
│  └─► SetupScreen (bootstrap progress)              │
│      └─► MainActivity (dashboard)                   │
│          ├─► DisplayActivity (desktop renderer)     │
│          ├─► TerminalActivity (terminal emulator)   │
│          ├─► LogViewerScreen (session logs)          │
│          └─► SettingsScreen (preferences)            │
│                                                     │
└─────────────────────────────────────────────────────┘
```

## MainActivity — Dashboard

### IDLE State

```
┌──────────────────────────────────────────────┐
│  ≡                    Xiaoian                │
├──────────────────────────────────────────────┤
│                                              │
│  ┌─────────────────────────────────────────┐ │
│  │  🖥  Desktop Environment                │ │
│  │                                         │ │
│  │  ┌──────────────┐  ┌─────────────────┐  │ │
│  │  │              │  │                 │  │ │
│  │  │  KDE Plasma  │  │     XFCE 4     │  │ │
│  │  │   Wayland    │  │      X11       │  │ │
│  │  │     ✓        │  │               │  │ │
│  │  └──────────────┘  └─────────────────┘  │ │
│  └─────────────────────────────────────────┘ │
│                                              │
│  ┌─────────────────────────────────────────┐ │
│  │  📺  Display Mode                       │ │
│  │                                         │ │
│  │  ○ Extend   ○ Mirror   ○ Local          │ │
│  │                                         │ │
│  │  External display: HDMI-1 (1920×1080)   │ │
│  └─────────────────────────────────────────┘ │
│                                              │
│                                              │
│         ┌────────────────────────┐           │
│         │                        │           │
│         │    ▶  START DESKTOP    │           │
│         │                        │           │
│         └────────────────────────┘           │
│                                              │
│  ┌──────────┐  ┌──────────┐                  │
│  │ Terminal │  │ Settings │                  │
│  └──────────┘  └──────────┘                  │
│                                              │
└──────────────────────────────────────────────┘
```

### RUNNING State

```
┌──────────────────────────────────────────────┐
│  ≡                    Xiaoian                │
├──────────────────────────────────────────────┤
│                                              │
│  ┌─────────────────────────────────────────┐ │
│  │  ● RUNNING — KDE Plasma 6              │ │
│  │                                         │ │
│  │  Mode:     Extend                       │ │
│  │  Display:  HDMI-1 (1920×1080)           │ │
│  │  Uptime:   1h 23m                       │ │
│  │  PID:      12345                        │ │
│  │  Phone:    Unlocked                     │ │
│  └─────────────────────────────────────────┘ │
│                                              │
│  ┌──────────────────┐  ┌──────────────────┐ │
│  │                  │  │                  │ │
│  │   🔒  LOCK      │  │   ⬛  STOP      │ │
│  │    PHONE        │  │   SESSION       │ │
│  │                  │  │                  │ │
│  └──────────────────┘  └──────────────────┘ │
│                                              │
│  ┌──────────────────┐  ┌──────────────────┐ │
│  │   >_  TERMINAL  │  │   📋  LOGS      │ │
│  └──────────────────┘  └──────────────────┘ │
│                                              │
│  ┌──────────────────────────────────────────┐│
│  │  Open Desktop (switch to display view)   ││
│  └──────────────────────────────────────────┘│
│                                              │
└──────────────────────────────────────────────┘
```

### INSTALLING/STARTING State

```
┌──────────────────────────────────────────────┐
│  ≡                    Xiaoian                │
├──────────────────────────────────────────────┤
│                                              │
│                                              │
│           ┌────────────────────┐             │
│           │  ⣾  Setting up... │             │
│           └────────────────────┘             │
│                                              │
│  ┌─────────────────────────────────────────┐ │
│  │ ████████████░░░░░░░░░░  52%             │ │
│  └─────────────────────────────────────────┘ │
│                                              │
│  [*] Downloading Debian rootfs...            │
│  [*] Extracting rootfs (1823 / 34521)        │
│  [*] Installing missing dependencies...      │
│  [*] Starting Anland daemon...               │
│                                              │
│                                              │
│         ┌────────────────────────┐           │
│         │       ✕  CANCEL        │           │
│         └────────────────────────┘           │
│                                              │
└──────────────────────────────────────────────┘
```

## SetupScreen — First Run

```
┌──────────────────────────────────────────────┐
│                                              │
│              Welcome to Xiaoian              │
│                                              │
│     Debian Desktop for your Android phone    │
│                                              │
│  ┌─────────────────────────────────────────┐ │
│  │                                         │ │
│  │  Setting up environment...              │ │
│  │                                         │ │
│  │  ████████████████░░░░░░░░  62%          │ │
│  │                                         │ │
│  │  Extracting bootstrap packages...       │ │
│  │                                         │ │
│  └─────────────────────────────────────────┘ │
│                                              │
│  ┌─────────────────────────────────────────┐ │
│  │  Steps:                                 │ │
│  │  ✓  Checking root access                │ │
│  │  ✓  Downloading bootstrap (48 MB)       │ │
│  │  ⣾  Extracting packages...              │ │
│  │  ○  Installing display server           │ │
│  │  ○  Installing audio server             │ │
│  │  ○  Validating environment              │ │
│  └─────────────────────────────────────────┘ │
│                                              │
│    This only happens once. After setup,      │
│    everything works offline.                 │
│                                              │
└──────────────────────────────────────────────┘
```

## TerminalActivity

```
┌──────────────────────────────────────────────┐
│  [Local #1]  [Chroot #1]  [+]          ⚙  × │
├──────────────────────────────────────────────┤
│                                              │
│  root@debian:~# apt list --installed | head  │
│  Listing... Done                             │
│  apt/now 2.9.8 arm64                         │
│  base-files/now 13.5 arm64                   │
│  bash/now 5.2.32-1 arm64                     │
│  coreutils/now 9.5-1 arm64                   │
│  dbus/now 1.14.10-4+b2 arm64                 │
│  dolphin/now 24.12.1-1 arm64                 │
│  firefox-esr/now 128.5.0esr-1 arm64          │
│  root@debian:~# █                            │
│                                              │
│                                              │
│                                              │
│                                              │
│                                              │
│                                              │
├──────────────────────────────────────────────┤
│ ESC │CTRL│ ALT│ TAB│  ← │  ↑ │  ↓ │  → │ / │
└──────────────────────────────────────────────┘
```

## SettingsScreen

```
┌──────────────────────────────────────────────┐
│  ←                  Settings                 │
├──────────────────────────────────────────────┤
│                                              │
│  GENERAL                                     │
│  ┌─────────────────────────────────────────┐ │
│  │ Default desktop environment    KDE ▼    │ │
│  ├─────────────────────────────────────────┤ │
│  │ Default display mode         Extend ▼   │ │
│  ├─────────────────────────────────────────┤ │
│  │ Auto-start on boot              [  ]    │ │
│  └─────────────────────────────────────────┘ │
│                                              │
│  DISPLAY                                     │
│  ┌─────────────────────────────────────────┐ │
│  │ Mirror mode DPI                 240     │ │
│  ├─────────────────────────────────────────┤ │
│  │ Lock: disable touchscreen       [✓]     │ │
│  ├─────────────────────────────────────────┤ │
│  │ Lock: turn off backlight        [✓]     │ │
│  └─────────────────────────────────────────┘ │
│                                              │
│  TERMINAL                                    │
│  ┌─────────────────────────────────────────┐ │
│  │ Font size                       14      │ │
│  ├─────────────────────────────────────────┤ │
│  │ Font family           JetBrains Mono ▼  │ │
│  ├─────────────────────────────────────────┤ │
│  │ Color scheme               Dracula ▼    │ │
│  └─────────────────────────────────────────┘ │
│                                              │
│  ADVANCED                                    │
│  ┌─────────────────────────────────────────┐ │
│  │ Infra root path      /data/local/... ▶  │ │
│  ├─────────────────────────────────────────┤ │
│  │ Update check interval          24h ▼    │ │
│  ├─────────────────────────────────────────┤ │
│  │ Reset environment                   ▶   │ │
│  ├─────────────────────────────────────────┤ │
│  │ Uninstall desktop                   ▶   │ │
│  └─────────────────────────────────────────┘ │
│                                              │
│  ABOUT                                       │
│  ┌─────────────────────────────────────────┐ │
│  │ Version                      1.0.0      │ │
│  │ Script version (KDE)         2.9.0      │ │
│  │ Script version (XFCE)        1.5.0      │ │
│  │ License                      GPL-3.0    │ │
│  │ Source code                  GitHub ▶    │ │
│  └─────────────────────────────────────────┘ │
│                                              │
└──────────────────────────────────────────────┘
```

## Navigation (Compose)

```kotlin
@Composable
fun XiaoianApp() {
    val navController = rememberNavController()

    NavHost(navController, startDestination = "dashboard") {
        composable("dashboard") { DashboardScreen(navController) }
        composable("settings") { SettingsScreen(navController) }
        composable("logs") { LogViewerScreen(navController) }
        // Terminal and Display are separate Activities (not Compose destinations)
        // because they need independent window management
    }
}
```

## Estimated Effort

| Screen | Time |
|---|---|
| Dashboard (IDLE + RUNNING + INSTALLING states) | 3–4 days |
| First-run setup screen | 1–2 days |
| Settings screen | 2–3 days |
| Log viewer | 1–2 days |
| Theme, typography, animations | 2–3 days |
| **Total** | **~2 weeks** |
