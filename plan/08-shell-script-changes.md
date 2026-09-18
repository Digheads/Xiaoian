# 08 — Shell Script Changes

## Overview

The existing shell scripts (`xiaoian-wayland-kde.sh` and `xiaoian-x11-xfce.sh`) remain the core engine. Only minimal changes are needed to support running from the Xiaoian app instead of a standalone Termux session.

**Where the scripts live:** the app variants are in `app/src/main/assets/` (bundled in the APK, copied to `/data/local/tmp` and run via `su -c` on every start). The scripts in the repo root are the standalone Termux **reference** versions and stay unchanged.

## Implemented: XFCE (script 1.6.0)

The app does not use a Xiaoian `$PREFIX` (Change 1 below) for XFCE. It removes the Termux dependency instead:

| Area | Before (Termux reference) | Now (`assets/xiaoian-x11-xfce.sh`) |
|---|---|---|
| `$PREFIX` / `PATH` | Termux `$PREFIX/bin` first | No `$PREFIX`; `PATH=/system/bin:/system/xbin` |
| `$TMPDIR` | `$PREFIX/tmp` (Termux) | `$INFRA_ROOT/tmp` (mode 1777), bind-mounted as chroot `/tmp` |
| X server | `$PREFIX/bin/termux-x11` | APK via `app_process … CmdEntryPoint`, with `TMPDIR=<rootfs>/tmp` |
| Downloads | `$PREFIX/bin/wget` | `http_get` / `http_cat` → app downloader `com.xiaoian.app.tools.Fetch` via `app_process`. No fallback |
| Rootfs `.tar.xz` extraction | `$PREFIX/bin/tar` | `find_xz_tar`: Termux tar if installed, else Magisk / KernelSU / APatch busybox with `-J` |
| Rootfs download | written directly to the target | `.part` file, renamed on success (a broken download is no longer treated as cached) |
| PulseAudio | required (Termux) | Optional: started only if Termux + `pulseaudio` exist, otherwise the desktop runs **without sound** |
| `am start` / lock target | `com.termux.x11` | `com.xiaoian.app` (`APP_PACKAGE`) |
| `TERMUX_UID` | Fallback `10422` | Empty when Termux is missing (only used for PulseAudio) |

Still open for XFCE: sound without Termux (see [09 R12](09-risks-and-mitigations.md#r12-sound-without-termux)).

## Implemented: KDE (script 2.10.0)

KDE followed XFCE off Termux. The display daemon was the last hard dependency:
it used to be a Termux package (`dpkg -i anland_<version>_aarch64.deb`) found on
`$PATH`. It now ships inside the APK.

| Area | Before (Termux reference) | Now (`assets/xiaoian-wayland-kde.sh`) |
|---|---|---|
| `$PREFIX` / `PATH` | Termux `$PREFIX/bin` first | No `$PREFIX`; `PATH=/system/bin:/system/xbin` |
| `$TMPDIR` | `$PREFIX/tmp` (Termux) | `/data/data/com.xiaoian.app/files/tmp`, bind-mounted as chroot `/tmp` |
| Anland daemon | `anland` on `$PATH` (Termux package, installed by hand with `dpkg -i`) | `$ANLAND_BIN` → `libanland.so` in the APK's native library dir, passed in by the app. The script does no discovery of its own |
| Daemon socket | `$TMPDIR/anland/display_daemon.sock` | `$TMPDIR/anland.sock` — what the daemon and the Android consumer actually use; the chroot sees it as `/tmp/anland.sock` |
| Daemon logs | discarded (`>/dev/null 2>&1`) | appended to `$LOG_FILE` — bind and client errors are the only clue when KWin cannot connect |
| Process matching | `DE_HOST_PATTERNS="^$PREFIX/bin/anland"` (empty `$PREFIX` → matched nothing) | derived from `$ANLAND_BIN`; `DE_HOST_NAMES` is its basename (`libanland.so`) |
| Downloads / extraction | `$PREFIX/bin/wget`, `$PREFIX/bin/tar` | `http_get` / `http_cat` and `extract_txz`, ported from the XFCE script (it never had them) |
| `am start` target | `com.anland.termux/.MainActivity` | `com.xiaoian.app/com.anland.termux.MainActivity` |

### Why the daemon binary is called `libanland.so`

`/data/data` is mounted non-executable (W^X), so a binary cannot simply be
dropped into the app's data directory and run. Android's packager extracts
files matching `lib*.so` from the APK into the app's native library directory
with the execute bit set, which is the standard way to ship an executable in an
APK. This requires `useLegacyPackaging = true` in the **application** module —
a library module's own packaging options do not affect the final APK.

### The app is the only caller

The scripts are started by the app and nothing else, so anything they need is
passed in rather than discovered. `ScriptEnv.prefix()` builds the environment
prefix in one place and every invocation uses it — start (`-s`), stop (`-t`),
lock (`-k`) and uninstall (`-u`) — so `$ANLAND_BIN` is set even on the paths
that only tear the session down, where `DE_HOST_PATTERNS` needs it to match the
running daemon.

`check_anland_daemon` distinguishes the two ways this can fail: `$ANLAND_BIN`
unset (the app did not pass it) and `$ANLAND_BIN` not executable (the APK lacks
the binary, or `useLegacyPackaging` is off).

The sections below are the original plan. For XFCE they have been superseded by the table above; they still apply to KDE.

## Change Summary

| Area | Change | Impact |
|---|---|---|
| `$PREFIX` detection | Dynamic instead of hardcoded | Both scripts, section 1 |
| `am start` targets | Xiaoian app instead of Anland/Termux:X11 | Both scripts, section 10 |
| `DE_APP_PACKAGE` | Xiaoian app package name | Both scripts, section 1 |
| `TERMUX_UID` | Support Xiaoian app's UID | Both scripts, section 1 |
| Lock target | Xiaoian app instead of Anland/Termux:X11 | Both scripts, section 6 |

## Change 1: Dynamic $PREFIX Detection

### Current (both scripts)

```bash
export PREFIX="/data/data/com.termux/files/usr"
```

### New

```bash
# Detect $PREFIX: Xiaoian app, Termux, or environment override
if [ -n "$XIAOIAN_PREFIX" ]; then
    export PREFIX="$XIAOIAN_PREFIX"
elif [ -d "/data/data/com.xiaoian.app/files/usr/bin" ]; then
    export PREFIX="/data/data/com.xiaoian.app/files/usr"
elif [ -d "/data/data/com.termux/files/usr/bin" ]; then
    export PREFIX="/data/data/com.termux/files/usr"
else
    echo "[!] ERROR: No Termux-compatible environment found."
    echo "[!] Install the Xiaoian app or Termux."
    exit 1
fi
```

This maintains full backward compatibility — users who run the scripts from Termux directly will see no change.

## Change 2: am start Targets

### Current — xiaoian-wayland-kde.sh

```bash
# Extend mode:
/system/bin/am start -n "$ANLAND_APP_PACKAGE"/.MainActivity \
    --display "$EXTERNAL_DISPLAY_ID" \
    --windowingMode 1 -f 0x18000000

# Mirror/local mode:
/system/bin/am start -n "$ANLAND_APP_PACKAGE"/.MainActivity
```

### New — xiaoian-wayland-kde.sh

```bash
XIAOIAN_APP_PACKAGE="com.xiaoian.app"

# Detect whether Xiaoian app is installed
if pm list packages 2>/dev/null | grep -q "$XIAOIAN_APP_PACKAGE"; then
    DISPLAY_PACKAGE="$XIAOIAN_APP_PACKAGE"
    DISPLAY_ACTIVITY=".DisplayActivity"
    DISPLAY_EXTRAS="--es display_mode wayland"
else
    DISPLAY_PACKAGE="$ANLAND_APP_PACKAGE"
    DISPLAY_ACTIVITY="/.MainActivity"
    DISPLAY_EXTRAS=""
fi

# Extend mode:
/system/bin/am start -n "$DISPLAY_PACKAGE$DISPLAY_ACTIVITY" \
    $DISPLAY_EXTRAS \
    --display "$EXTERNAL_DISPLAY_ID" \
    --windowingMode 1 -f 0x18000000

# Mirror/local mode:
/system/bin/am start -n "$DISPLAY_PACKAGE$DISPLAY_ACTIVITY" \
    $DISPLAY_EXTRAS
```

### Current — xiaoian-x11-xfce.sh

```bash
# Extend mode:
/system/bin/am start -n "$TERMUX_X11_PACKAGE"/com.termux.x11.MainActivity \
    --display "$EXTERNAL_DISPLAY_ID" --windowingMode 1 -f 0x18000000

# Mirror/local mode:
/system/bin/am start -n "$TERMUX_X11_PACKAGE"/com.termux.x11.MainActivity
```

### New — xiaoian-x11-xfce.sh

```bash
XIAOIAN_APP_PACKAGE="com.xiaoian.app"

if pm list packages 2>/dev/null | grep -q "$XIAOIAN_APP_PACKAGE"; then
    DISPLAY_PACKAGE="$XIAOIAN_APP_PACKAGE"
    DISPLAY_ACTIVITY=".DisplayActivity"
    DISPLAY_EXTRAS="--es display_mode x11"
else
    DISPLAY_PACKAGE="$TERMUX_X11_PACKAGE"
    DISPLAY_ACTIVITY="/com.termux.x11.MainActivity"
    DISPLAY_EXTRAS=""
fi

# Same am start pattern as above
```

## Change 3: DE_APP_PACKAGE

### Current

```bash
# Wayland/KDE:
DE_APP_PACKAGE="$ANLAND_APP_PACKAGE"

# X11/XFCE:
DE_APP_PACKAGE="$TERMUX_X11_PACKAGE"
```

### New

```bash
# The app package for force-stop on teardown
if pm list packages 2>/dev/null | grep -q "com.xiaoian.app"; then
    DE_APP_PACKAGE="com.xiaoian.app"
else
    # Fallback to standalone apps
    DE_APP_PACKAGE="$ANLAND_APP_PACKAGE"  # or $TERMUX_X11_PACKAGE
fi
```

> **Note:** We probably do NOT want to `am force-stop com.xiaoian.app` during teardown because the app's Service manages the session. Instead, the teardown should signal the app via an intent or just kill the display server processes. This needs careful consideration.

**Better approach:**

```bash
# Don't force-stop the Xiaoian app during teardown.
# The session wrapper's cleanup handles the display processes.
# The app's watchdog detects the session end and updates its state.
if [ "$DE_APP_PACKAGE" = "com.xiaoian.app" ]; then
    # Just finish the DisplayActivity, not the whole app
    /system/bin/am broadcast -a com.xiaoian.app.SESSION_ENDED 2>/dev/null
else
    am force-stop "$DE_APP_PACKAGE" 2>/dev/null
fi
```

## Change 4: TERMUX_UID

### Current

```bash
TERMUX_UID=$(stat -c "%u" /data/data/com.termux/files/usr/bin/bash 2>/dev/null || echo 10422)
```

### New

```bash
if [ -d "/data/data/com.xiaoian.app/files/usr" ]; then
    TERMUX_UID=$(stat -c "%u" /data/data/com.xiaoian.app/files/usr/bin/bash 2>/dev/null || echo 10422)
else
    TERMUX_UID=$(stat -c "%u" /data/data/com.termux/files/usr/bin/bash 2>/dev/null || echo 10422)
fi
```

## Change 5: Lock Target (extend mode)

### Current — xiaoian-wayland-kde.sh (do_lock)

```bash
/system/bin/am start --display "$EXT_ID" \
    -n "$ANLAND_APP_PACKAGE"/.MainActivity \
    --windowingMode 1 -f 0x18000000
```

### New

```bash
/system/bin/am start --display "$EXT_ID" \
    -n "$DISPLAY_PACKAGE$DISPLAY_ACTIVITY" \
    $DISPLAY_EXTRAS \
    --windowingMode 1 -f 0x18000000
```

## Implementation Strategy

### Option A: Modify existing scripts (recommended)

Add a detection block at the top of each script. All changes are backward-compatible — scripts still work from standalone Termux.

**Pros:** Minimal diff, easy to review, no diverging codebases.
**Cons:** Scripts get slightly more complex.

### Option B: Separate app-specific scripts

Create `xiaoian-wayland-kde-app.sh` and `xiaoian-x11-xfce-app.sh` that source the originals and override specific variables.

**Pros:** Original scripts untouched.
**Cons:** Two codebases to maintain, easy to drift.

### Recommendation: **Option A**. The changes are small and isolated. A single `if` block near the top handles detection, and the rest of the script works identically.

## Testing Matrix

| Launch method | $PREFIX | Display app | Expected behavior |
|---|---|---|---|
| Termux shell, no Xiaoian app | Termux | Anland/T:X11 | Original behavior, unchanged |
| Termux shell, Xiaoian app installed | Xiaoian | Xiaoian | Uses Xiaoian renderer |
| Xiaoian app UI | Xiaoian (via env var) | Xiaoian | Full app experience |
| Xiaoian app + `XIAOIAN_PREFIX` override | Custom | Xiaoian | Developer testing |

## Estimated Effort

| Task | Time |
|---|---|
| $PREFIX detection block | 2h |
| am start target detection | 3h |
| Lock target adaptation | 1h |
| TERMUX_UID adaptation | 1h |
| Teardown / force-stop handling | 3h |
| Testing all combinations | 1 day |
| **Total** | **~2–3 days** |
