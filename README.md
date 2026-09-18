# Xiaoian

**Debian for Android** — a full Linux desktop environment on Xiaomi (and other Android) phones, powered by a `chroot`-based Debian container.

Two variants are available:

| Script | Desktop Environment | Display | Audio |
|---|---|---|---|
| `xiaoian-wayland-kde.sh` | **KDE Plasma 6** | Wayland (Anland) | PipeWire + pipewire-pulse (chroot) |
| `xiaoian-x11-xfce.sh` | **XFCE 4** | X11 (Termux:X11) | PulseAudio (Termux, unix socket) |

---

## Table of Contents

- [Overview](#overview)
- [Prerequisites](#prerequisites)
- [Installation](#installation)
- [Usage](#usage)
  - [Actions](#actions)
  - [Modes](#modes)
  - [Combining Short Options](#combining-short-options)
- [Command Reference](#command-reference)
  - [--start (-s) — Start](#--start--s----start)
  - [--stop (-t) — Stop](#--stop--t----stop)
  - [--lock (-k) — Virtual Lock](#--lock--k----virtual-lock)
  - [--exsize (-e) — External Display Resolution](#--exsize--e----external-display-resolution)
  - [--insize (-i) — Restore Internal Resolution](#--insize--i----restore-internal-resolution)
  - [--uninstall (-u) — Full Removal](#--uninstall--u----full-removal)
  - [--version (-v) — Version Info](#--version--v----version-info)
  - [--help (-h) — Help](#--help--h----help)
- [Comparing the Two Scripts](#comparing-the-two-scripts)
- [Architecture & Internals](#architecture--internals)
  - [Directory Layout](#directory-layout)
  - [Startup Flow](#startup-flow)
  - [Session Management](#session-management)
  - [Mount System](#mount-system)
  - [Updates & Offline Operation](#updates--offline-operation)
  - [Audio](#audio)
  - [GPU Acceleration](#gpu-acceleration)
- [Troubleshooting](#troubleshooting)
- [Uninstallation](#uninstallation)
- [License](#license)

---

## Overview

The Xiaoian scripts are fully automated, single-file shell solutions that set up and run a complete Debian Linux desktop environment on a rooted Android phone via Termux. The system:

- **Automatically downloads** the latest Debian Trixie (arm64) rootfs from the LXC image server
- **Automatically installs** all required packages inside the chroot
- **Automatically fetches** GPU drivers (Freedreno/KGSL Mesa) from GitHub
- **Supports three display modes**: extend (extended desktop), mirror (screen mirroring), local (phone screen)
- **Provides virtual phone locking** when using an external display
- **Works offline** if the required components are already cached
- The session **keeps running in the background** after closing Termux

---

## Prerequisites

### Required for Both Scripts

| Requirement | Description |
|---|---|
| **Root access** | The scripts use `chroot`, `mount`, and system settings — root is required |
| **Termux** | Scripts must be run from a Termux shell |
| **Termux packages** | `wget`, `tar` (from Termux `$PREFIX/bin`) — typically pre-installed |
| **Internet connection** | Required for the first install (rootfs + package downloads). Not needed for subsequent starts if everything is cached |
| **External display** | Required for `extend` and `mirror` modes (wired HDMI, USB-C DisplayPort, or Miracast/WiDi) |

### For the Wayland/KDE Variant (`xiaoian-wayland-kde.sh`)

| Requirement | Description |
|---|---|
| **Anland daemon** | A Termux package that provides the Wayland display server. It is **NOT** automatically installed — you must download and install it manually |
| **Anland app** | The `com.anland.termux` Android application (the display frontend) |

Installing Anland:

```bash
# In Termux:
# 1. Download the .deb package from:
#    https://github.com/lfdevs/anland-termux/releases
#    (look for anland_<version>_aarch64.deb)

# 2. Install it:
cd ~
pkg install dpkg        # if not already installed
dpkg -i anland_<version>_aarch64.deb

# 3. Verify:
which anland
```

### For the X11/XFCE Variant (`xiaoian-x11-xfce.sh`)

| Requirement | Description |
|---|---|
| **Termux:X11** | The `com.termux.x11` app and its corresponding Termux package (`termux-x11`) |
| **PulseAudio** | Termux package (`pkg install pulseaudio`) — the script starts PulseAudio on the Termux side |

---

## Installation

### Downloading the Scripts

```bash
# In Termux:
pkg install git
git clone https://github.com/Digheads/Xiaoian.git
cd Xiaoian
chmod +x xiaoian-wayland-kde.sh xiaoian-x11-xfce.sh
```

### Or manually:

```bash
# In Termux:
pkg install wget
wget https://raw.githubusercontent.com/Digheads/Xiaoian/main/xiaoian-wayland-kde.sh
wget https://raw.githubusercontent.com/Digheads/Xiaoian/main/xiaoian-x11-xfce.sh
chmod +x xiaoian-*.sh
```

### First Run

The first run automatically performs the full installation:

```bash
# In a root shell (su):
su
./xiaoian-wayland-kde.sh -s      # KDE Plasma 6
# or
./xiaoian-x11-xfce.sh -s         # XFCE 4
```

During the first start, the script will:
1. Download the Debian Trixie arm64 rootfs (~200 MB compressed)
2. Extract it into the chroot directory
3. Download GPU drivers (Freedreno/Mesa) from GitHub
4. **(KDE only)** Download XWayland, KWin Anland backend packages
5. Mount the required filesystems
6. Run the in-chroot package installation (`apt-get install`)
7. Launch the desktop environment

> **Note:** The first installation may take 10–30 minutes depending on network speed and phone performance.

---

## Usage

### Syntax

```
xiaoian-wayland-kde.sh <action> [mode]
xiaoian-x11-xfce.sh    <action> [mode]
```

If no action is specified, the default is `--start`.

### Actions

| Short | Long | Description |
|---|---|---|
| `-s` | `--start` | Install (if needed) and start the system |
| `-t` | `--stop` | Stop the running system |
| `-u` | `--uninstall` | Delete everything under the infra root |
| `-k` | `--lock` | Virtually lock the phone (extend/mirror mode) |
| `-e` | `--exsize` | *(mirror only)* Resize framework to external display |
| `-i` | `--insize` | *(mirror only)* Restore framework to phone resolution |
| `-v` | `--version` | Show version |
| `-h` | `--help` | Show help |

### Modes

Modes can **only be combined with `--start`**, and **only one** may be specified at a time.

| Short | Long | Description |
|---|---|---|
| `-x` | `--extend` | **(default)** Extended desktop on external display — the phone screen remains independent |
| `-m` | `--mirror` | Mirror the phone screen to the external display |
| `-l` | `--local` | The desktop environment runs on the phone's built-in screen |

### Combining Short Options

Short options can be combined after a single dash:

```bash
./xiaoian-wayland-kde.sh -sl     # = -s -l (start + local mode)
./xiaoian-wayland-kde.sh -sx     # = -s -x (start + extend mode)
./xiaoian-x11-xfce.sh -sm       # = -s -m (start + mirror mode)
```

---

## Command Reference

### `--start` (`-s`) — Start

The most important command. Step by step, it:

1. **Checks** that no session is already running
2. **Configures Android global settings** for the selected mode:
   - `enable_freeform_support`
   - `force_desktop_mode_on_external_displays`
   - `enable_non_resizable_multi_window`
   - `force_resizable_activities`
   - If any of these changed → **a reboot is required**, the script exits with a warning
3. **In extend mode**, verifies that an external display is connected
4. **Downloads/updates** components from GitHub (24-hour cache)
5. **Installs the Debian rootfs** (first time only, from the LXC server)
6. **Mounts chroot filesystems** (proc, sys, dev, tmp, home, etc.)
7. **Installs packages** inside the chroot (only if needed)
8. **Starts the display server:**
   - **(KDE)** Starts the Anland daemon, waits for its socket
   - **(XFCE)** Starts the Termux:X11 server and PulseAudio
9. **Launches the display app** on the appropriate screen
10. **Starts the session wrapper** in the background (via `setsid`)
11. **Waits** for the desktop environment to appear (max ~12s)

#### Examples

```bash
# Default start (extend mode):
su -c ./xiaoian-wayland-kde.sh

# Explicit extend mode:
su -c './xiaoian-wayland-kde.sh -s --extend'

# Mirror mode:
su -c './xiaoian-x11-xfce.sh -s -m'

# Local mode (on the phone screen):
su -c './xiaoian-wayland-kde.sh -sl'
```

---

### `--stop` (`-t`) — Stop

Performs a graceful shutdown:

1. Sends **SIGTERM** to the session wrapper
2. Waits up to 10 seconds for it to finish
3. If it hasn't stopped → **SIGKILL**
4. **Full teardown:**
   - Stops chroot processes (TERM → KILL)
   - Stops the display server
   - **(XFCE)** Stops PulseAudio
   - **(KDE)** Stops the Anland daemon
   - Unmounts all filesystems
   - Restores phone state (unlocks, resets resolution)
   - Cleans up state files

```bash
su -c './xiaoian-wayland-kde.sh -t'
su -c './xiaoian-x11-xfce.sh --stop'
```

> Chroot applications (Firefox, etc.) receive SIGTERM first so they can save their state. SIGKILL is only sent after ~3 seconds if they haven't exited.

---

### `--lock` (`-k`) — Virtual Lock

Only works in **extend** and **mirror** modes. "Turns off" the phone screen while the desktop continues running on the external display:

1. **Disables the touchscreen** (`/sys/class/input/*/inhibited`)
2. **Turns off the backlight** (`/sys/class/backlight/*/brightness`)
3. **Sets screen-off timeout** to the maximum value
4. **(Extend mode)** Relaunches the Anland/Termux:X11 app on the external display
5. **Starts a power button watcher** — pressing the power button unlocks the phone

```bash
su -c './xiaoian-wayland-kde.sh -k'
# To unlock: press the POWER button
```

#### Unlock Mechanism

The script spawns a background process (`getevent -l`) that monitors for `KEY_POWER DOWN` events. When detected:
- Restores the touchscreen
- Resets the screen timeout
- Removes the lock state file
- Exits

---

### `--exsize` (`-e`) — External Display Resolution

**Only available in mirror mode.** Sets the Android framework resolution to match the connected external display (at 240 DPI), so the mirrored image optimally fills the external screen.

```bash
su -c './xiaoian-x11-xfce.sh -e'
```

The resolution is automatically detected from `dumpsys display` output.

---

### `--insize` (`-i`) — Restore Internal Resolution

**Only available in mirror mode.** Resets the Android framework resolution and DPI to the phone's native values.

```bash
su -c './xiaoian-x11-xfce.sh -i'
```

---

### `--uninstall` (`-u`) — Full Removal

Deletes everything under the infrastructure root (`INFRA_ROOT`):

1. Verifies no session is running (stop it first if there is one)
2. Unmounts all mounts
3. **Detailed per-directory deletion** (deletes `usr/` and `var/` subdirectories individually for progress feedback)
4. Removes the entire infrastructure root (including downloaded components)

```bash
su -c './xiaoian-wayland-kde.sh -t'     # stop first
su -c './xiaoian-wayland-kde.sh -u'     # then uninstall
```

> **KDE variant:** The Anland daemon Termux package is **not** automatically removed. To remove it manually: `dpkg -r anland`

After uninstallation, the script can be re-run (`-s`) for a clean install.

---

### `--version` (`-v`) — Version Info

Prints the script version, date, author, the stack in use, infrastructure paths, and audio configuration.

```bash
su -c './xiaoian-wayland-kde.sh -v'
```

Example output:
```
Version: 2.9.0
Date:    2026-09-17
Author:  Digheads Ferke
Stack:   Debian chroot + KDE Plasma 6 (Wayland) + Anland
Root:    /data/local/xiaoian-wayland-kde
Installer: /data/local/xiaoian-wayland-kde/install_files
Audio:   chroot PipeWire + WirePlumber + pipewire-pulse
         (Anland forwards speaker and mic; logs: $TMPDIR/anland/*.log)
Anland:  external Termux package (not auto-downloaded)
Screen:  locker disabled (no root password in chroot)
Logout:  watchdog terminates session when plasmashell is gone
Updates: GitHub checked at most every 24h; works offline with cache
```

---

### `--help` (`-h`) — Help

Prints the built-in usage summary.

---

## Comparing the Two Scripts

| Feature | `xiaoian-wayland-kde.sh` | `xiaoian-x11-xfce.sh` |
|---|---|---|
| **Version** | 2.9.0 | 1.5.0 |
| **Desktop Environment** | KDE Plasma 6 | XFCE 4 |
| **Display Protocol** | Wayland | X11 |
| **Display Server** | Anland (Termux package) | Termux:X11 |
| **Infra Root** | `/data/local/xiaoian-wayland-kde` | `/data/local/xiaoian-x11-xfce` |
| **Audio** | PipeWire + WirePlumber + pipewire-pulse (runs in chroot, Anland handles speaker/mic forwarding) | PulseAudio (runs on Termux side, via unix socket) |
| **GPU** | Freedreno (KGSL), env: `MESA_LOADER_DRIVER_OVERRIDE=kgsl` | Freedreno/Turnip Vulkan → Zink, fallback: llvmpipe |
| **Downloaded Components** | XWayland .deb, KWin Anland backend .zip, Freedreno .tar.gz, startplasma-anland.sh | Freedreno .tar.gz |
| **External Termux Dependency** | Anland daemon (manual install!) | Termux:X11, PulseAudio |
| **Watchdog** | Yes — monitors plasmashell (~3s timeout) → session termination | No — session wrapper waits on chroot process |
| **D-Bus Divert** | Yes — problematic system service files diverted via dpkg-divert | No |
| **DE Configuration** | Baloo indexing disabled, KDE Connect disabled, screen locker disabled, power management disabled | XFCE compositing disabled (xfwm4) |
| **Resource Usage** | Higher (full KDE Plasma stack) | Lower (XFCE is lightweight) |

### When to Choose Which?

- **KDE Plasma 6** (`xiaoian-wayland-kde.sh`): For a modern, full-featured desktop experience with Wayland, a better window manager, and you don't mind higher resource usage.
- **XFCE 4** (`xiaoian-x11-xfce.sh`): For a lightweight, fast environment that uses less memory and CPU, or when the Anland daemon is unavailable or incompatible with your device.

---

## Architecture & Internals

### Directory Layout

```
/data/local/xiaoian-wayland-kde/        # (or xiaoian-x11-xfce/)
├── debian/                             # Debian Trixie arm64 rootfs (chroot)
│   ├── bin/, usr/, var/, etc/, ...     # Standard Linux filesystem
│   ├── start-kde.sh (or start-xfce.sh) # In-chroot session launcher
│   ├── setup-pkgs.sh                   # In-chroot package installer
│   └── .component-*.installed          # Component version marker files
├── install_files/                      # Downloaded components cache
│   ├── debian-trixie-rootfs-*.tar.xz   # Rootfs tarball
│   ├── mesa-for-android-container*.tar.gz
│   ├── xwayland_*.deb                  # (KDE only)
│   ├── kwin_anland-*.zip               # (KDE only)
│   ├── startplasma-anland.sh           # (KDE only)
│   └── .checked-*                      # Update check timestamps
├── lib.sh                              # Shared helper functions
├── session.sh                          # Background session wrapper script
├── watcher.sh                          # Power button watcher
├── state                               # "<wrapper PID> <boot ID>"
├── mode                                # "extend" | "mirror" | "local"
├── locked                              # Existence = phone is locked
├── watcher.pid                         # Watcher PID
└── de_debug.log                        # Detailed log file
```

### Startup Flow

```
xiaoian.sh --start
    │
    ├─ Clear stale state (after previous crash/reboot)
    ├─ Check/apply Android global settings
    ├─ Detect external display (extend/mirror)
    │
    ├─ Download/update components (GitHub API → wget)
    │   └─ 24-hour cache, offline fallback
    │
    ├─ Install Debian rootfs (if not present)
    │   └─ LXC → wget → tar xJf
    │
    ├─ Chroot mounts (proc, sys, dev, dev/pts, dev/shm, run, tmp, home)
    │
    ├─ In-chroot package installation (setup-pkgs.sh)
    │   ├─ apt-get install (missing packages)
    │   ├─ Freedreno driver installation
    │   ├─ (KDE) XWayland, KWin Anland backend installation
    │   └─ (KDE) Unwanted package removal (baloo, akonadi, etc.)
    │
    ├─ Start display server
    │   ├─ (KDE) Anland daemon (setsid, socket polling)
    │   └─ (XFCE) Termux:X11 + PulseAudio (unix socket)
    │
    ├─ Mirror mode: wm size / wm density adjustment
    ├─ Launch display app (am start)
    │
    ├─ Generate session script (start-kde.sh / start-xfce.sh)
    │   ├─ Environment variables (GPU, audio, locale)
    │   ├─ D-Bus daemon startup
    │   └─ dbus-run-session → desktop environment
    │
    ├─ Generate and start session wrapper (setsid, background)
    │   ├─ trap TERM/INT/HUP → cleanup (teardown_session)
    │   ├─ chroot → start-kde.sh / start-xfce.sh
    │   └─ (KDE) Watchdog: monitors plasmashell
    │
    └─ Save state file (PID + boot ID)
```

### Session Management

Session state is tracked via the `state` file, which contains the wrapper PID and the system's boot ID. This ensures that:

- **After a reboot**, stale state is automatically cleared (boot ID mismatch)
- **PID reuse** is detected via a `/proc/<pid>/cmdline` check, filtering out false positives
- **After a crash**, the next start automatically cleans up (`reset_stale_state`)

The **session wrapper** sets up traps that call the `teardown_session()` function:
- `EXIT` → on normal exit
- `TERM` → on signal from `--stop`
- `INT` → on Ctrl+C
- `HUP` → on terminal hangup

### Mount System

The chroot uses the following mounts:

| Mount Point | Type | Source / Notes |
|---|---|---|
| `proc` | `proc` | Virtual filesystem |
| `sys` | `sysfs` | Kernel interface |
| `run` | `tmpfs` | Fresh tmpfs per session (no stale sockets) |
| `dev` | bind | Binds `/dev` |
| `dev/pts` | bind | Binds `/dev/pts` |
| `dev/shm` | `tmpfs` | Shared memory |
| `tmp` | bind | Binds Termux `$TMPDIR` |
| `home` | bind | `/data/media/0` → the phone's internal storage |

Unmounting happens in reverse order (nested mounts first). Each mount gets 3 attempts; if a normal `umount` fails, `umount -l` (lazy) is used as a fallback.

### Updates & Offline Operation

The `fetch_asset()` function implements intelligent caching:

1. **If** the component is cached AND the last check is less than 24 hours old → **no network request**
2. **If** an update check is needed → GitHub API query (`releases/latest`, then `releases?per_page=20`)
3. **If** the download fails (offline, rate limited) → **the cached version is used** (if available)
4. **Downloads** go to a `.part` file — a failed download never corrupts the existing cache

### Audio

#### KDE (Wayland) Variant

```
[Application] → PipeWire/pipewire-pulse (inside chroot)
    → Anland KWin backend (speaker/mic forwarding)
    → Android audio system
```

- PipeWire, WirePlumber, and pipewire-pulse run inside the chroot
- The Anland helper script (`startplasma-anland.sh`) starts and manages them
- Both speaker output AND microphone input are supported

#### XFCE (X11) Variant

```
[Application] → libpulse/ALSA (inside chroot)
    → unix socket (/tmp/xiaoian-pulse/native)
    → PulseAudio (Termux side)
    → module-sles-sink → Android audio system
```

- PulseAudio runs on the Termux side, connected via a unix socket
- Chroot ALSA and PulseAudio clients connect through the `/tmp/xiaoian-pulse/native` socket
- No TCP port is opened

### GPU Acceleration

Both scripts use the **Freedreno (KGSL)** Mesa driver for hardware GPU acceleration with Qualcomm Adreno GPUs:

#### KDE Variant
```bash
export MESA_LOADER_DRIVER_OVERRIDE=kgsl
export TURNIP_KMD=kgsl
export GALLIUM_DRIVER=freedreno
export FD_FORCE_KGSL=1
export XWAYLAND_FORCE_KGSL_SURFACELESS=1
```

#### XFCE Variant
If the Freedreno/Turnip Vulkan ICD (`freedreno_icd.aarch64.json`) is available:
```bash
export GALLIUM_DRIVER=zink          # OpenGL → Vulkan wrapper
export MESA_LOADER_DRIVER_OVERRIDE=zink
export TU_DEBUG=noconform
export VK_ICD_FILENAMES=/usr/share/vulkan/icd.d/freedreno_icd.aarch64.json
```
If not available, automatic fallback to software rendering:
```bash
export LIBGL_ALWAYS_SOFTWARE=1
export GALLIUM_DRIVER=llvmpipe
```

---

## Troubleshooting

### General Issues

| Problem | Solution |
|---|---|
| `[!] A session is already running` | Stop it first: `./xiaoian-*.sh -t` |
| `[!] Global display settings were changed... AFTER A REBOOT` | Reboot the phone, then re-run the script |
| `[!] extend mode requested, but no external display is connected` | Connect an external display, or use `-m` (mirror) or `-l` (local) mode |
| Mounts won't unmount | `su -c 'cat /proc/mounts \| grep xiaoian'` — manually: `umount -l <mount point>` |
| Script hangs on startup | Check the log: `/data/local/xiaoian-*/de_debug.log` |

### KDE-Specific Issues

| Problem | Solution |
|---|---|
| `[!] Anland daemon NOT found in Termux` | Install Anland manually (see [Prerequisites](#prerequisites)) |
| `[!] Anland socket not found after 30s` | `logcat -d \| grep -i anland` — check the Android log |
| plasmashell won't start / session terminates | Check: `cat /data/local/xiaoian-wayland-kde/de_debug.log` |
| Watchdog terminates the session | plasmashell has been absent for ~3 seconds → normal logout behavior |

### XFCE-Specific Issues

| Problem | Solution |
|---|---|
| `[!] X server socket never appeared after 20s` | Verify that the `termux-x11` Termux package is installed |
| `[!] PulseAudio socket not found; XFCE will have no sound` | `pkg install pulseaudio` in Termux |
| `Cannot open display :0` | The X server failed to start → check the log |

### Log Files

```bash
# Session log:
cat /data/local/xiaoian-wayland-kde/de_debug.log
cat /data/local/xiaoian-x11-xfce/de_debug.log

# Previous run's log:
cat /data/local/xiaoian-*/de_debug.log.1

# Android system log:
logcat -d | grep -iE 'anland|termux'
```

---

## Uninstallation

### Full Removal

```bash
su
./xiaoian-wayland-kde.sh -t       # stop
./xiaoian-wayland-kde.sh -u       # uninstall

# KDE: remove the Anland daemon manually:
dpkg -r anland
```

### What It Deletes

- The entire Debian rootfs (`/data/local/xiaoian-*/debian/`)
- Downloaded components (`/data/local/xiaoian-*/install_files/`)
- The entire infrastructure root
- State files, logs, wrapper scripts

### What It Does NOT Delete

- Termux and its packages
- The Anland/Termux:X11 Android app
- Android global settings (freeform, desktop mode — these can be reset manually or revert on reboot if no longer needed)

---

## License

GPLv3 License — Copyright (c) 2026 Digheads

See the full license in the [LICENSE](LICENSE) file.
