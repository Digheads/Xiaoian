#!/system/bin/sh

# =====================================================================
# xiaoian.sh — Debian chroot + KDE Plasma 6 (Wayland) + Anland launcher
# Version: 2.9.0
# Date:    2026-09-17
# Author:  Digheads Ferke
# =====================================================================

# =====================================================================
# 1. CONFIG
# =====================================================================
SCRIPT_VERSION="2.9.0"
SCRIPT_DATE="2026-09-17"
SCRIPT_AUTHOR="Digheads Ferke"

export PREFIX="/data/data/com.termux/files/usr"
export PATH="$PREFIX/bin:$PREFIX/bin/applets:/system/bin:/system/xbin:$PATH"
export TMPDIR="$PREFIX/tmp"
export XDG_RUNTIME_DIR="$TMPDIR"

: "${HOME:=$PREFIX/../home}"
export HOME

TERMUX_UID=$(stat -c "%u" /data/data/com.termux/files/usr/bin/bash 2>/dev/null || echo 10422)

INFRA_ROOT="/data/local/xiaoian-wayland-kde"
LIB_FILE="$INFRA_ROOT/lib.sh"
LOG_FILE="$INFRA_ROOT/de_debug.log"
INSTALLER_DIR="$INFRA_ROOT/install_files"

# Update checks against GitHub happen at most this often (seconds).
UPDATE_CHECK_INTERVAL=86400

ANLAND_REPO="lfdevs/anland-termux"
MESA_REPO="lfdevs/mesa-for-android-container"
ANLAND_HELPER_URL="https://raw.githubusercontent.com/${ANLAND_REPO}/main/scripts/startplasma-anland.sh"

ANLAND_APP_PACKAGE="com.anland.termux"

# Desktop-specific values consumed by the common library (section 3).
DE_APP_PACKAGE="$ANLAND_APP_PACKAGE"
DE_SESSION_PROC="kwin_wayland"
DE_HOST_NAMES="anland"
DE_HOST_PATTERNS="^$PREFIX/bin/anland"

# =====================================================================
# 2. ARGUMENT PARSING
# =====================================================================
ACTION=""
MODE="extend"
MODE_SET=0

usage() {
    cat <<'EOF'
Usage: xiaoian.sh <action> [mode]

Actions:
  -s, --start       Install (if needed) and start the system
  -t, --stop        Stop the running system
  -u, --uninstall   Delete everything under the infra root
  -k, --lock        Virtually lock the phone (extend/mirror mode)
  -e, --exsize      (mirror only) Resize framework to external display
  -i, --insize      (mirror only) Restore framework to phone resolution
  -v, --version     Show version
  -h, --help        Show this help

Modes (only with --start, only one may be given):
  -x, --extend      (default) Extended desktop on external display
  -m, --mirror      Mirror phone screen to external display
  -l, --local       Run on the built-in phone screen

Short options can be combined:  -sl  is the same as  -s -l
                                -sx  is the same as  -s -x

Infrastructure root: /data/local/xiaoian-wayland-kde
Audio: chroot PipeWire + pipewire-pulse; Anland forwards speaker and mic.
Anland daemon: external Termux package (not auto-downloaded).
EOF
}

handle_short() {
    local c="$1"
    case "$c" in
        s) ACTION="start" ;;
        t) ACTION="stop" ;;
        u) ACTION="uninstall" ;;
        k) ACTION="lock" ;;
        e) ACTION="exsize" ;;
        i) ACTION="insize" ;;
        v) ACTION="version" ;;
        m) [ "$MODE_SET" = "1" ] && { echo "[!] Only one mode may be specified."; exit 1; }; MODE="mirror"; MODE_SET=1 ;;
        x) [ "$MODE_SET" = "1" ] && { echo "[!] Only one mode may be specified."; exit 1; }; MODE="extend"; MODE_SET=1 ;;
        l) [ "$MODE_SET" = "1" ] && { echo "[!] Only one mode may be specified."; exit 1; }; MODE="local";  MODE_SET=1 ;;
        h) usage; exit 0 ;;
        *) echo "[!] Unknown short option: -$c"; echo ""; usage; exit 1 ;;
    esac
}

for arg in "$@"; do
    case "$arg" in
        --start)     ACTION="start" ;;
        --stop)      ACTION="stop" ;;
        --uninstall) ACTION="uninstall" ;;
        --lock)      ACTION="lock" ;;
        --exsize)    ACTION="exsize" ;;
        --insize)    ACTION="insize" ;;
        --version)   ACTION="version" ;;
        --mirror|--extend|--local)
            if [ "$MODE_SET" = "1" ]; then
                echo "[!] Only one mode may be specified: --mirror, --extend, or --local."
                exit 1
            fi
            MODE="${arg#--}"
            MODE_SET=1
            ;;
        --help|-h)   usage; exit 0 ;;
        --*)         echo "[!] Unknown argument: $arg"; echo ""; usage; exit 1 ;;
        -?)          handle_short "${arg#-}" ;;
        -*)
            chars="${arg#-}"
            while [ -n "$chars" ]; do
                c="${chars%"${chars#?}"}"
                chars="${chars#?}"
                handle_short "$c"
            done
            ;;
        *) echo "[!] Unknown argument: $arg"; echo ""; usage; exit 1 ;;
    esac
done

[ -z "$ACTION" ] && ACTION="start"

mkdir -p "$INFRA_ROOT" 2>/dev/null

# =====================================================================
# 3. COMMON LIBRARY
# This section is identical in both xiaoian scripts (Wayland/KDE and
# X11/XFCE). It is written to $LIB_FILE so the detached session
# wrapper runs exactly the same cleanup code as this script.
# =====================================================================
write_lib() {
    {
        printf "INFRA_ROOT='%s'\n"       "$INFRA_ROOT"
        printf "TMPDIR='%s'\n"           "$TMPDIR"
        printf "DE_APP_PACKAGE='%s'\n"   "$DE_APP_PACKAGE"
        printf "DE_HOST_NAMES='%s'\n"    "$DE_HOST_NAMES"
        printf "DE_HOST_PATTERNS='%s'\n" "$DE_HOST_PATTERNS"
        cat << 'LIB'
DEBIAN_ROOTFS="$INFRA_ROOT/debian"
STATE_FILE="$INFRA_ROOT/state"
LOCK_STATE_FILE="$INFRA_ROOT/locked"
MODE_FILE="$INFRA_ROOT/mode"
WATCHER_SCRIPT="$INFRA_ROOT/watcher.sh"
WATCHER_PID_FILE="$INFRA_ROOT/watcher.pid"
SESSION_WRAPPER="$INFRA_ROOT/session.sh"

# Unmount order matters: nested mounts first.
CHROOT_MOUNTS="home dev/shm tmp dev/pts dev run sys proc"

# ---- session state --------------------------------------------------
# The state file holds "<wrapper pid> <boot id>". The boot id makes a
# state file left over from before a reboot invalid, and the cmdline
# check guards against the pid being reused by another process.
boot_id() { cat /proc/sys/kernel/random/boot_id 2>/dev/null; }

write_state() {
    echo "$1 $(boot_id)" > "$STATE_FILE"
    chmod 0644 "$STATE_FILE"
}

state_pid() {
    local pid boot
    [ -f "$STATE_FILE" ] || return 1
    read -r pid boot < "$STATE_FILE" || return 1
    [ -n "$pid" ] || return 1
    [ -z "$boot" ] || [ "$boot" = "$(boot_id)" ] || return 1
    grep -qF "$SESSION_WRAPPER" "/proc/$pid/cmdline" 2>/dev/null || return 1
    echo "$pid"
}

is_running() { state_pid >/dev/null; }
is_locked()  { [ -f "$LOCK_STATE_FILE" ]; }
saved_mode() { [ -f "$MODE_FILE" ] && cat "$MODE_FILE" 2>/dev/null; }

# ---- phone state (virtual lock, mirror resize) -----------------------
find_touch_inhibit() {
    local d
    for d in /sys/class/input/input*; do
        [ -f "$d/inhibited" ] || continue
        if [ "$(cat "$d/name" 2>/dev/null)" = "fts_ts" ]; then
            echo "$d/inhibited"; return 0
        fi
    done
    [ -f /sys/class/input/input5/inhibited ] && \
        echo "/sys/class/input/input5/inhibited" && return 0
    return 1
}

find_backlight() {
    local d
    for d in /sys/class/backlight/*/; do
        [ -f "${d}brightness" ] && echo "${d}brightness" && return 0
    done
    return 1
}

kill_watcher() {
    local wp
    if [ -f "$WATCHER_PID_FILE" ]; then
        read -r wp < "$WATCHER_PID_FILE"
        [ -n "$wp" ] && kill "$wp" 2>/dev/null
        rm -f "$WATCHER_PID_FILE"
    fi
    # pkill scans /proc natively and never matches itself.
    pkill -9 -f "$WATCHER_SCRIPT" 2>/dev/null
    rm -f "$WATCHER_SCRIPT"
}

# Undo the virtual lock and the mirror-mode resize. Idempotent.
restore_phone_state() {
    local ti
    if [ -f "$LOCK_STATE_FILE" ]; then
        ti=$(find_touch_inhibit)
        [ -n "$ti" ] && echo 0 > "$ti" 2>/dev/null
        settings put system screen_off_timeout 300000 2>/dev/null
        rm -f "$LOCK_STATE_FILE"
    fi
    if [ "$(saved_mode)" = "mirror" ]; then
        wm size reset 2>/dev/null
        wm density reset 2>/dev/null
    fi
    rm -f "$MODE_FILE"
}

# State files that survived a reboot or a crashed wrapper.
reset_stale_state() {
    [ -f "$STATE_FILE" ] || [ -f "$MODE_FILE" ] || [ -f "$LOCK_STATE_FILE" ] || return 0
    is_running && return 0
    echo "[*] Clearing stale session state (reboot or crashed session)..."
    kill_watcher
    restore_phone_state
    rm -f "$STATE_FILE"
}

# ---- processes --------------------------------------------------------
# Pure shell test (-ef compares inodes): no fork per process, ~12x
# faster than readlink/basename on a phone with ~800 processes.
find_chroot_pids() {
    local p
    for p in /proc/[0-9]*; do
        [ "$p/root" -ef "$DEBIAN_ROOTFS" ] || continue
        [ "${p#/proc/}" = "$$" ] && continue
        echo "${p#/proc/}"
    done
}

# Termux-side helpers of the desktop: exact process names plus
# anchored cmdline patterns, never a bare word.
stop_host_procs() {
    local sig="$1" n p
    for n in $DE_HOST_NAMES; do killall -"$sig" "$n" 2>/dev/null; done
    for p in $DE_HOST_PATTERNS; do pkill -"$sig" -f "$p" 2>/dev/null; done
}

wait_chroot_empty() {
    local n=0
    while [ "$n" -lt "$1" ]; do
        [ -z "$(find_chroot_pids)" ] && return 0
        sleep 0.1
        n=$((n + 1))
    done
    [ -z "$(find_chroot_pids)" ]
}

# TERM first so apps (Firefox, the desktop) can save their state,
# KILL only what is still alive after ~3s.
stop_chroot_procs() {
    local pids
    stop_host_procs TERM
    pids=$(find_chroot_pids)
    [ -n "$pids" ] && kill -TERM $pids 2>/dev/null
    wait_chroot_empty 15
    stop_host_procs KILL
    pids=$(find_chroot_pids)
    if [ -n "$pids" ]; then
        kill -KILL $pids 2>/dev/null
        wait_chroot_empty 10 || \
            echo "[!] WARNING: $(find_chroot_pids | wc -l) chrooted process(es) still alive."
    fi
    [ -n "$DE_APP_PACKAGE" ] && am force-stop "$DE_APP_PACKAGE" 2>/dev/null
}

# ---- mounts -----------------------------------------------------------
is_mounted() { grep -q " $DEBIAN_ROOTFS/$1 " /proc/mounts 2>/dev/null; }

mount_all() {
    local m
    for m in proc sys run dev tmp home; do
        mkdir -p "$DEBIAN_ROOTFS/$m"
    done
    # /run is a fresh tmpfs per session: no stale sockets, locks or
    # iceauth files survive. Old on-disk leftovers are removed once.
    is_mounted run || rm -rf "$DEBIAN_ROOTFS/run/user" 2>/dev/null

    is_mounted proc    || mount -t proc proc "$DEBIAN_ROOTFS/proc"
    is_mounted sys     || mount -t sysfs sys "$DEBIAN_ROOTFS/sys"
    is_mounted run     || mount -t tmpfs -o mode=0755,nosuid,nodev tmpfs "$DEBIAN_ROOTFS/run"
    is_mounted dev     || mount --bind /dev "$DEBIAN_ROOTFS/dev"
    is_mounted dev/pts || mount --bind /dev/pts "$DEBIAN_ROOTFS/dev/pts"
    is_mounted dev/shm || mount -t tmpfs -o mode=1777 tmpfs "$DEBIAN_ROOTFS/dev/shm"
    is_mounted tmp     || mount --bind "$TMPDIR" "$DEBIAN_ROOTFS/tmp"
    is_mounted home    || mount --bind /data/media/0 "$DEBIAN_ROOTFS/home"

    mkdir -p "$DEBIAN_ROOTFS/run/dbus" "$DEBIAN_ROOTFS/run/lock" "$DEBIAN_ROOTFS/run/user/0"
    chmod 1777 "$DEBIAN_ROOTFS/run/lock"
    chmod 0700 "$DEBIAN_ROOTFS/run/user/0"
}

unmount_all() {
    local m target tries
    stop_chroot_procs
    sync
    for m in $CHROOT_MOUNTS; do
        target="$DEBIAN_ROOTFS/$m"
        tries=0
        while is_mounted "$m" && [ "$tries" -lt 3 ]; do
            umount "$target" 2>/dev/null || umount -l "$target" 2>/dev/null
            is_mounted "$m" || break
            tries=$((tries + 1))
            sleep 0.2
        done
    done
    if grep -q " $DEBIAN_ROOTFS/" /proc/mounts 2>/dev/null; then
        echo "[!] WARNING: mount(s) still present under $DEBIAN_ROOTFS:"
        grep " $DEBIAN_ROOTFS/" /proc/mounts
        return 1
    fi
    return 0
}

# Full teardown used by --stop and by the session wrapper on exit.
teardown_session() {
    kill_watcher
    unmount_all
    restore_phone_state
    rm -f "$STATE_FILE"
}
LIB
    } > "$LIB_FILE.tmp" && mv -f "$LIB_FILE.tmp" "$LIB_FILE"
}

write_lib || { echo "[!] ERROR: cannot write $LIB_FILE"; exit 1; }
. "$LIB_FILE"

# ---- script-only helpers (the wrapper does not need these) -----------
is_fresh() {
    [ -f "$1" ] || return 1
    [ $(( $(date +%s) - $(stat -c %Y "$1" 2>/dev/null || echo 0) )) -lt "$UPDATE_CHECK_INTERVAL" ]
}

rotate_log() {
    [ -s "$LOG_FILE" ] && mv -f "$LOG_FILE" "$LOG_FILE.1"
    : > "$LOG_FILE"
    chmod 0644 "$LOG_FILE"
}

# Poll until the desktop process shows up (success) or the wrapper
# dies (failure); a live wrapper after the timeout counts as success.
wait_for_session() {
    local wrapper_pid="$1" i=0
    while [ "$i" -lt 60 ]; do
        kill -0 "$wrapper_pid" 2>/dev/null || return 1
        pidof "$DE_SESSION_PROC" >/dev/null 2>&1 && return 0
        sleep 0.2
        i=$((i + 1))
    done
    kill -0 "$wrapper_pid" 2>/dev/null
}

safe_wipe_rootfs() {
    unmount_all || {
        echo "[!] Refusing to rm -rf: mounts still active under $DEBIAN_ROOTFS."
        echo "[!] Unmount them manually then re-run."
        return 1
    }
    rm -rf "$DEBIAN_ROOTFS" 2>/dev/null
    return 0
}

# Usage: fetch_asset <repo> <file-name regex> <friendly name> <old-version glob>
# Offline-friendly: a cached asset is used as-is when the last update
# check is younger than UPDATE_CHECK_INTERVAL, or when GitHub cannot be
# reached (offline / API rate limit). Downloads go to a .part file, so
# a failed download never destroys the cached copy.
fetch_asset() {
    local repo="$1" pattern="$2" friendly="$3" old_glob="$4"
    local slug stamp cached api json matches url fname target f
    slug=$(printf '%s' "$friendly" | tr -c 'A-Za-z0-9' '_')
    stamp="$INSTALLER_DIR/.checked-$slug"
    cached=$(ls -t "$INSTALLER_DIR" 2>/dev/null | grep -E "^${pattern}\$" | head -1)

    if [ -n "$cached" ] && is_fresh "$stamp"; then
        echo "[*] $friendly: $cached (cached, checked recently)"
        return 0
    fi

    url=""
    for api in "releases/latest" "releases?per_page=20"; do
        json=$(timeout 20 "$PREFIX/bin/wget" -q -o /dev/null -O - \
            "https://api.github.com/repos/${repo}/${api}" 2>/dev/null)
        [ -n "$json" ] || continue
        matches=$(printf '%s\n' "$json" | tr ',' '\n' \
            | grep -oE '"browser_download_url"[[:space:]]*:[[:space:]]*"[^"]*"' \
            | sed 's/.*"browser_download_url"[[:space:]]*:[[:space:]]*"//;s/"$//' \
            | grep -iE "/${pattern}\$")
        if [ -n "$matches" ]; then
            url=$(printf '%s\n' "$matches" | grep -i "debian" | head -1)
            [ -z "$url" ] && url=$(printf '%s\n' "$matches" | head -1)
            break
        fi
    done

    if [ -z "$url" ]; then
        if [ -n "$cached" ]; then
            echo "[!] $friendly: update check failed (offline or rate-limited); using cached $cached"
            return 0
        fi
        echo "[!] No matching asset for $friendly (pattern: $pattern) and nothing cached."
        return 1
    fi

    fname=${url##*/}
    target="$INSTALLER_DIR/$fname"
    if [ -s "$target" ]; then
        echo "[*] $friendly: $fname (up to date)"
    else
        echo "[*] Downloading $friendly: $fname"
        rm -f "$target.part"
        if ! timeout 900 "$PREFIX/bin/wget" -q -o /dev/null -O "$target.part" "$url" \
           || [ ! -s "$target.part" ]; then
            rm -f "$target.part"
            if [ -n "$cached" ]; then
                echo "[!] Download failed; keeping cached $cached"
                return 0
            fi
            echo "[!] Download failed for $friendly."
            return 1
        fi
        mv -f "$target.part" "$target"
        echo "[*] Saved to: $target ($(du -h "$target" 2>/dev/null | cut -f1))"
    fi

    for f in "$INSTALLER_DIR"/$old_glob; do
        [ -f "$f" ] && [ "$f" != "$target" ] && rm -f "$f"
    done
    touch "$stamp"
    return 0
}

ensure_installer_dir() {
    mkdir -p "$INSTALLER_DIR" 2>/dev/null
    [ -d "$INSTALLER_DIR" ] || { echo "[!] Failed to create installer directory: $INSTALLER_DIR"; exit 1; }
}

SESSION_SCRIPT="$DEBIAN_ROOTFS/start-kde.sh"
SETUP_SCRIPT="$DEBIAN_ROOTFS/setup-pkgs.sh"

detect_external_res() {
    dumpsys display 2>/dev/null \
        | grep -i 'mBaseDisplayInfo' \
        | grep -iE 'VIRTUAL|EXTERNAL|WIFI' \
        | grep -oE 'real [0-9]{3,4} x [0-9]{3,4}' \
        | head -1 \
        | tr -cd '0-9x'
}

detect_external_display_id() {
    dumpsys display 2>/dev/null \
        | grep -oE 'DisplayViewport\{type=EXTERNAL[^}]*\}' \
        | grep -oE 'displayId=[0-9]+' \
        | head -1 \
        | cut -d= -f2
}

apply_external_size() {
    RAW_RES=$(detect_external_res)
    if [ -z "$RAW_RES" ]; then
        echo "[!] No external display detected; cannot apply external size."
        return 1
    fi
    W=$(echo "$RAW_RES" | cut -d'x' -f1)
    H=$(echo "$RAW_RES" | cut -d'x' -f2)
    if [ -z "$W" ] || [ -z "$H" ]; then
        echo "[!] Could not parse external resolution: $RAW_RES"
        return 1
    fi
    if [ "$W" -gt "$H" ]; then PORTRAIT_RES="${H}x${W}"; else PORTRAIT_RES="${W}x${H}"; fi
    echo "[*] External: $RAW_RES -> framework set to $PORTRAIT_RES @ 240 DPI"
    wm size "$PORTRAIT_RES"
    wm density 240
    return 0
}

restore_internal_size() {
    echo "[*] Restoring framework to native phone resolution..."
    wm size reset 2>/dev/null
    wm density reset 2>/dev/null
}

# =====================================================================
# 3c. ANLAND DAEMON (TERMUX SIDE)
# =====================================================================
check_anland_in_termux() {
    if ! command -v anland >/dev/null 2>&1; then
        echo ""
        echo "[!] ================================================================"
        echo "[!] Anland daemon NOT found in Termux."
        echo "[!]"
        echo "[!] This is a required external dependency. It is NOT downloaded"
        echo "[!] or installed by this script. You must install it manually."
        echo "[!]"
        echo "[!] Download the Termux package from:"
        echo "[!]   https://github.com/${ANLAND_REPO}/releases"
        echo "[!]   (look for anland_<version>_aarch64.deb)"
        echo "[!]"
        echo "[!] Then install it in Termux:"
        echo "[!]   cd ~"
        echo "[!]   pkg install dpkg        # if not already installed"
        echo "[!]   dpkg -i anland_<version>_aarch64.deb"
        echo "[!]"
        echo "[!] After installation, verify with:  which anland"
        echo "[!] Then re-run this script."
        echo "[!] ================================================================"
        return 1
    fi
    echo "[*] Anland daemon: $(command -v anland)"
    return 0
}

start_anland_daemon() {
    echo "[*] Starting Anland display daemon (Termux side)..."

    stop_host_procs KILL
    i=0
    while pidof anland >/dev/null 2>&1 && [ $i -lt 20 ]; do
        sleep 0.1
        i=$((i+1))
    done
    rm -f "$TMPDIR/anland/display_daemon.sock" 2>/dev/null

    mkdir -p "$TMPDIR/anland"
    chmod 777 "$TMPDIR/anland"

    setsid anland >/dev/null 2>&1 &
    ANLAND_PID=$!
    echo "[*] Anland daemon pid: $ANLAND_PID"

    echo "[*] Waiting for display daemon socket at $TMPDIR/anland/display_daemon.sock..."
    i=0
    while [ $i -lt 150 ]; do
        [ -S "$TMPDIR/anland/display_daemon.sock" ] && break
        if ! kill -0 "$ANLAND_PID" 2>/dev/null; then
            echo "[!] Anland daemon exited prematurely."
            break
        fi
        i=$((i+1))
        sleep 0.2
    done

    if [ -S "$TMPDIR/anland/display_daemon.sock" ]; then
        echo "[*] Anland display daemon socket is up (took $((i/5))s)."
        return 0
    else
        echo "[!] WARNING: Anland socket not found after 30s."
        echo "[!]   logcat -d | grep -i anland"
        echo "[!]   ls -la $TMPDIR/anland/"
        echo "[!]   pm list packages | grep anland"
        return 1
    fi
}

# =====================================================================
# 4. POWER BUTTON WATCHER
# =====================================================================
spawn_unlock_watcher() {
    kill_watcher
    cat << WATCHER > "$WATCHER_SCRIPT"
#!/system/bin/sh

LOCK_STATE_FILE="$LOCK_STATE_FILE"

[ -f "\$LOCK_STATE_FILE" ] || exit 0

TI=""
for d in /sys/class/input/input*; do
    [ -f "\$d/name" ] || continue
    if [ "\$(cat "\$d/name" 2>/dev/null)" = "fts_ts" ] && [ -f "\$d/inhibited" ]; then
        TI="\$d/inhibited"
        break
    fi
done

getevent -l 2>/dev/null | while read -r line; do
    case "\$line" in
        *KEY_POWER*DOWN*)
            input keyevent KEYCODE_WAKEUP 2>/dev/null
            settings put system screen_off_timeout 300000 2>/dev/null
            if [ -n "\$TI" ] && [ -f "\$TI" ]; then
                echo 0 > "\$TI" 2>/dev/null
            fi
            rm -f "\$LOCK_STATE_FILE"
            exit 0
            ;;
    esac
done
WATCHER
    chmod +x "$WATCHER_SCRIPT"
    if command -v setsid >/dev/null 2>&1; then
        setsid "$WATCHER_SCRIPT" </dev/null >/dev/null 2>&1 &
    else
        nohup "$WATCHER_SCRIPT" </dev/null >/dev/null 2>&1 &
    fi
    echo $! > "$WATCHER_PID_FILE"
    echo "[*] Power button watcher started (pid $(cat "$WATCHER_PID_FILE"))"
}

# =====================================================================
# 5. ACTION: --version
# =====================================================================
do_version() {
    echo "Version: $SCRIPT_VERSION"
    echo "Date:    $SCRIPT_DATE"
    echo "Author:  $SCRIPT_AUTHOR"
    echo "Stack:   Debian chroot + KDE Plasma 6 (Wayland) + Anland"
    echo "Root:    $INFRA_ROOT"
    echo "Installer: $INSTALLER_DIR"
    echo "Audio:   chroot PipeWire + WirePlumber + pipewire-pulse"
    echo "         (Anland forwards speaker and mic; logs: \$TMPDIR/anland/*.log)"
    echo "Anland:  external Termux package (not auto-downloaded)"
    echo "Screen:  locker disabled (no root password in chroot)"
    echo "Logout:  watchdog terminates session when plasmashell is gone"
    echo "Updates: GitHub checked at most every $((UPDATE_CHECK_INTERVAL / 3600))h; works offline with cache"
}

# =====================================================================
# 6. ACTION: --lock
# =====================================================================
do_lock() {
    echo "[*] Locking phone (virtual lock)..."

    if ! is_running; then
        echo "[!] No running session detected."
        echo "[!] Start the system first with: $0 -s"
        exit 1
    fi

    if [ "$(saved_mode)" = "local" ]; then
        echo "[!] Lock is not applicable in local mode."
        exit 1
    fi

    if is_locked; then
        echo "[*] Phone is already locked. Restarting watcher..."
        kill_watcher
    fi

    TI=$(find_touch_inhibit)
    if [ -n "$TI" ] && [ -f "$TI" ]; then
        echo "[*] Inhibiting touchscreen: $TI"
        echo 1 > "$TI" 2>/dev/null
    else
        echo "[!] Touchscreen inhibit path not found; skipping."
    fi

    BL=$(find_backlight)
    if [ -n "$BL" ] && [ -f "$BL" ]; then
        echo "[*] Turning off backlight: $BL"
        echo 0 > "$BL" 2>/dev/null
    else
        echo "[!] Backlight path not found; skipping."
    fi

    echo "[*] Setting screen_off_timeout to 2147483647..."
    settings put system screen_off_timeout 2147483647 2>/dev/null

    if [ "$(saved_mode)" = "extend" ]; then
        EXT_ID=$(detect_external_display_id)
        if [ -n "$EXT_ID" ]; then
            echo "[*] Ensure Anland on external display (id=$EXT_ID)..."
            /system/bin/am start --display "$EXT_ID" \
                -n "$ANLAND_APP_PACKAGE"/.MainActivity \
                --windowingMode 1 -f 0x18000000 2>/dev/null
        else
            echo "[!] No external display detected; Anland not relaunched."
        fi
    else
        echo "[*] Mirror mode: Anland stays on default display."
    fi

    echo "locked" > "$LOCK_STATE_FILE"
    spawn_unlock_watcher
    echo ""
    echo "[*] Phone locked."
    echo "[*] Press the POWER button to unlock."
}

# =====================================================================
# 7. ACTION: --exsize / --insize
# =====================================================================
require_mirror_session() {
    if ! is_running; then
        echo "[!] No running session detected."
        exit 1
    fi
    SM=$(saved_mode)
    if [ "$SM" != "mirror" ]; then
        echo "[!] This command only works in mirror mode (current: ${SM:-unknown})."
        exit 1
    fi
}

do_exsize() {
    require_mirror_session
    echo "[*] Applying external display size..."
    apply_external_size || exit 1
    echo "[*] Done."
}

do_insize() {
    require_mirror_session
    restore_internal_size
    echo "[*] Done."
}

# =====================================================================
# 8. ACTION: --stop
# =====================================================================
# Ask the wrapper to shut down (its trap runs the graceful teardown);
# SIGKILL it only if it has not finished within 10s.
stop_session_wrapper() {
    local pid n=0
    pid=$(state_pid) || { echo "[*] No running session; doing direct cleanup."; return 0; }
    echo "[*] Signaling session (pid $pid)..."
    kill -TERM -"$pid" 2>/dev/null || kill -TERM "$pid" 2>/dev/null
    while [ -d "/proc/$pid" ] && [ "$n" -lt 100 ]; do
        sleep 0.1
        n=$((n+1))
    done
    if [ -d "/proc/$pid" ]; then
        echo "[!] Session did not finish in 10s; forcing SIGKILL..."
        kill -9 -"$pid" 2>/dev/null || kill -9 "$pid" 2>/dev/null
    fi
}

do_stop() {
    echo "[*] Stopping Xiaoian (Wayland + KDE)..."

    stop_session_wrapper
    teardown_session
    rm -f "$SESSION_WRAPPER" "$TMPDIR/anland/display_daemon.sock"

    echo ""
    echo "[*] Xiaoian stopped."
}

# =====================================================================
# 9. ACTION: --uninstall
# =====================================================================
do_uninstall() {
    if is_running; then
        echo "[!] A session is currently running."
        echo "[!] Stop it first: $0 -t"
        exit 1
    fi

    echo "[*] Uninstalling Xiaoian (Wayland + KDE)..."

    teardown_session || {
        echo "[!] Refusing to delete: mount(s) still active under $DEBIAN_ROOTFS."
        exit 1
    }

    if [ -d "$DEBIAN_ROOTFS" ]; then
        echo "[*] Counting files (this can take a few seconds)..."
        TOTAL=$(find "$DEBIAN_ROOTFS" 2>/dev/null | wc -l)
        echo "[*] Removing $TOTAL files..."
        echo ""

        for entry in "$DEBIAN_ROOTFS"/* "$DEBIAN_ROOTFS"/.[!.]* "$DEBIAN_ROOTFS"/..?*; do
            [ -e "$entry" ] || continue
            name=$(basename "$entry")

            if [ "$name" = "usr" ] || [ "$name" = "var" ]; then
                printf '      %s/ ...\n' "$name"
                for sub in "$entry"/* "$entry"/.[!.]*; do
                    [ -e "$sub" ] || continue
                    subname=$(basename "$sub")
                    printf '        %s/%s ... ' "$name" "$subname"
                    rm -rf "$sub" 2>/dev/null
                    printf 'done\n'
                done
                rm -rf "$entry" 2>/dev/null
            else
                printf '      %-15s ... ' "$name"
                rm -rf "$entry" 2>/dev/null
                printf 'done\n'
            fi
        done

        rmdir "$DEBIAN_ROOTFS" 2>/dev/null
    fi

    if [ -d "$INFRA_ROOT" ]; then
        printf '      %-15s ... ' "infra root"
        rm -rf "$INFRA_ROOT" 2>/dev/null
        printf 'done\n'
    fi

    if [ -d "$INFRA_ROOT" ]; then
        echo "[!] WARNING: $INFRA_ROOT could not be fully removed."
        ls -la "$INFRA_ROOT" 2>/dev/null | head -20
    else
        echo ""
        echo "[*] Xiaoian fully uninstalled."
        echo "[*] $INFRA_ROOT has been removed (including installer files)."
        echo "[*] NOTE: the Anland daemon remains installed in Termux."
        echo "[*]       Remove it manually with: dpkg -r anland"
    fi
    echo "[*] You can now re-run '$0 -s' for a clean install."
}

# =====================================================================
# 10. ACTION: --start
# =====================================================================
do_start() {
    if is_running; then
        echo "[!] A session is already running (pid $(state_pid))."
        echo "[!] Stop it first: $0 -t"
        exit 1
    fi

    rm -f "$MODE_FILE"

    if [ "$MODE" != "local" ]; then
        if [ "$MODE" = "mirror" ]; then
            WANT_FREEFORM=1; WANT_DESKTOP=0; WANT_NONRESIZE=0; WANT_RESIZE=0
        else
            WANT_FREEFORM=1; WANT_DESKTOP=1; WANT_NONRESIZE=1; WANT_RESIZE=1
        fi

        CUR_FREEFORM=$(settings get global enable_freeform_support 2>/dev/null)
        CUR_DESKTOP=$(settings get global force_desktop_mode_on_external_displays 2>/dev/null)
        CUR_NONRESIZE=$(settings get global enable_non_resizable_multi_window 2>/dev/null)
        CUR_RESIZE=$(settings get global force_resizable_activities 2>/dev/null)
        [ "$CUR_FREEFORM" = "null" ] && CUR_FREEFORM=0
        [ "$CUR_DESKTOP"  = "null" ] && CUR_DESKTOP=0
        [ "$CUR_NONRESIZE" = "null" ] && CUR_NONRESIZE=0
        [ "$CUR_RESIZE"   = "null" ] && CUR_RESIZE=0

        echo "[*] Mode: $MODE"
        echo "[*] Current global settings:"
        echo "      enable_freeform_support              = $CUR_FREEFORM (want $WANT_FREEFORM)"
        echo "      force_desktop_mode_on_external_displ = $CUR_DESKTOP (want $WANT_DESKTOP)"
        echo "      enable_non_resizable_multi_window    = $CUR_NONRESIZE (want $WANT_NONRESIZE)"
        echo "      force_resizable_activities           = $CUR_RESIZE (want $WANT_RESIZE)"

        if [ "$CUR_FREEFORM" != "$WANT_FREEFORM" ] \
           || [ "$CUR_DESKTOP" != "$WANT_DESKTOP" ] \
           || [ "$CUR_NONRESIZE" != "$WANT_NONRESIZE" ] \
           || [ "$CUR_RESIZE" != "$WANT_RESIZE" ]; then

            echo ""
            echo "[*] Applying new global settings for $MODE mode..."
            settings put global enable_freeform_support "$WANT_FREEFORM"
            settings put global force_desktop_mode_on_external_displays "$WANT_DESKTOP"
            settings put global enable_non_resizable_multi_window "$WANT_NONRESIZE"
            settings put global force_resizable_activities "$WANT_RESIZE"

            echo ""
            echo "[!] ================================================================"
            echo "[!] Global display settings were changed. These only take effect"
            echo "[!] AFTER A REBOOT."
            echo "[!]"
            echo "[!] Please reboot, then re-run:  $0 -s --$MODE"
            echo "[!] ================================================================"
            exit 0
        fi

        echo "[*] Global settings already match $MODE mode. Proceeding."
    else
        echo "[*] Mode: local (built-in display, skipping global settings)"
    fi

    EXTERNAL_DISPLAY_ID=""
    if [ "$MODE" = "extend" ]; then
        EXTERNAL_DISPLAY_ID=$(detect_external_display_id)
        if [ -z "$EXTERNAL_DISPLAY_ID" ]; then
            echo "[!] ERROR: extend mode requested, but no external display is connected."
            echo "[!] Connect the wireless/HDMI display and re-run."
            exit 1
        fi
        echo "[*] External display detected: displayId=$EXTERNAL_DISPLAY_ID"
    fi

    ensure_installer_dir
    echo ""
    echo "[*] Root:      $INFRA_ROOT"
    echo "[*] Installer: $INSTALLER_DIR"

    check_anland_in_termux || exit 1

    echo "[*] Checking upstream components..."
    fetch_asset "$ANLAND_REPO" "xwayland_24\\.1\\.6-91_arm64\\.deb" \
        "XWayland" "xwayland_*.deb" || exit 1
    fetch_asset "$ANLAND_REPO" "kwin_anland-[^/]*debian[^/]*\\.zip" \
        "KWin Anland backend" "kwin_anland-*.zip" || exit 1
    fetch_asset "$MESA_REPO" "mesa-for-android-container_[^/]*_debian_trixie_arm64\\.tar\\.gz" \
        "Freedreno driver" "mesa-for-android-container*.tar.gz" || exit 1

    # The helper is refreshed together with the other components, so it
    # never drifts away from the KWin backend it belongs to.
    ANLAND_HELPER_LOCAL="$INSTALLER_DIR/startplasma-anland.sh"
    HELPER_STAMP="$INSTALLER_DIR/.checked-anland-helper"
    if [ -s "$ANLAND_HELPER_LOCAL" ] && is_fresh "$HELPER_STAMP"; then
        echo "[*] startplasma-anland.sh: cached, checked recently"
    else
        echo "[*] Checking startplasma-anland.sh..."
        rm -f "$ANLAND_HELPER_LOCAL.part"
        if timeout 60 "$PREFIX/bin/wget" -q -o /dev/null -O "$ANLAND_HELPER_LOCAL.part" "$ANLAND_HELPER_URL" \
           && [ -s "$ANLAND_HELPER_LOCAL.part" ]; then
            if [ -s "$ANLAND_HELPER_LOCAL" ] && \
               [ "$(md5sum < "$ANLAND_HELPER_LOCAL.part")" = "$(md5sum < "$ANLAND_HELPER_LOCAL")" ]; then
                rm -f "$ANLAND_HELPER_LOCAL.part"
                echo "[*] startplasma-anland.sh is up to date."
            else
                mv -f "$ANLAND_HELPER_LOCAL.part" "$ANLAND_HELPER_LOCAL"
                echo "[*] startplasma-anland.sh updated."
            fi
            touch "$HELPER_STAMP"
        else
            rm -f "$ANLAND_HELPER_LOCAL.part"
            if [ -s "$ANLAND_HELPER_LOCAL" ]; then
                echo "[!] startplasma-anland.sh check failed; using cached copy."
            else
                echo "[!] ERROR: Failed to download startplasma-anland.sh"
                exit 1
            fi
        fi
    fi
    chmod +x "$ANLAND_HELPER_LOCAL"

    XWAYLAND_DEB=""; KWIN_ZIP=""; FREEDRENO_TAR=""
    for f in "$INSTALLER_DIR"/xwayland_*.deb; do [ -f "$f" ] && XWAYLAND_DEB="$f" && break; done
    for f in "$INSTALLER_DIR"/kwin_anland-*.zip; do [ -f "$f" ] && KWIN_ZIP="$f" && break; done
    for f in "$INSTALLER_DIR"/mesa-for-android-container*.tar.gz; do [ -f "$f" ] && FREEDRENO_TAR="$f" && break; done

    if [ -z "$XWAYLAND_DEB" ] || [ -z "$KWIN_ZIP" ] || [ -z "$FREEDRENO_TAR" ]; then
        echo "[!] ERROR: One or more chroot components are missing after download."
        exit 1
    fi

    echo "[*] Components:"
    echo "      $(basename "$XWAYLAND_DEB") [chroot package]"
    echo "      $(basename "$KWIN_ZIP")     [chroot package]"
    echo "      $(basename "$FREEDRENO_TAR") [chroot package]"
    echo "      $(basename "$ANLAND_HELPER_LOCAL")"

    if [ -d "$DEBIAN_ROOTFS/data/data/com.termux" ]; then
        echo "[*] Removing leftover Termux payload from chroot (old bug)..."
        rm -rf "$DEBIAN_ROOTFS/data"
    fi

    if [ -f "$DEBIAN_ROOTFS/bin/bash" ]; then
        echo "[*] Debian rootfs found. Skipping installation."
    else
        echo "[*] Debian rootfs NOT found. Starting automated fresh installation..."
        safe_wipe_rootfs || exit 1
        mkdir -p "$DEBIAN_ROOTFS"

        INDEX_URL="https://images.linuxcontainers.org/streams/v1/images.json"
        echo "[*] Fetching latest rootfs date from LXC JSON index..."
        LATEST_DATE=$(timeout 30 $PREFIX/bin/wget -q -o /dev/null -O - "$INDEX_URL" \
            | tr ',' '\n' \
            | grep -o 'debian/trixie/arm64/default/[0-9]\{8\}_[0-9]\{2\}:[0-9]\{2\}' \
            | sort | tail -1 | sed 's#.*/##')

        if [ -z "$LATEST_DATE" ]; then
            echo "[!] ERROR: Failed to fetch the latest rootfs date from LXC server."
            exit 1
        fi

        DOWNLOAD_URL="https://images.linuxcontainers.org/images/debian/trixie/arm64/default/$LATEST_DATE/rootfs.tar.xz"
        ROOTFS_TARBALL="$INSTALLER_DIR/debian-trixie-rootfs-$LATEST_DATE.tar.xz"

        if [ -s "$ROOTFS_TARBALL" ]; then
            echo "[*] Rootfs tarball already cached: $(basename "$ROOTFS_TARBALL")"
        else
            echo "[*] Downloading archive: $DOWNLOAD_URL"
            timeout 1800 "$PREFIX/bin/wget" -q -o /dev/null -O "$ROOTFS_TARBALL" "$DOWNLOAD_URL" \
                || { echo "[!] ERROR: Download failed!"; exit 1; }
        fi

        for old in "$INSTALLER_DIR"/debian-trixie-rootfs-*.tar.xz; do
            [ -f "$old" ] && [ "$old" != "$ROOTFS_TARBALL" ] && rm -f "$old"
        done

        echo "[*] Extracting rootfs (this will take a few minutes)..."
        $PREFIX/bin/tar -xJf "$ROOTFS_TARBALL" -C "$DEBIAN_ROOTFS" \
            || { echo "[!] ERROR: Extraction failed!"; exit 1; }
        echo "[*] Debian rootfs installation successfully completed!"
    fi

    echo "[*] Removing previous session locks..."
    unmount_all
    rm -rf $TMPDIR/.X11-unix $TMPDIR/.X0-lock \
           $DEBIAN_ROOTFS/tmp/.X11-unix $DEBIAN_ROOTFS/tmp/.X0-lock 2>/dev/null

    if [ ! -d /dev/shm ]; then
        mkdir -p /dev/shm 2>/dev/null
    fi
    if [ -d /dev/shm ]; then
        chmod 1777 /dev/shm 2>/dev/null
    else
        echo "[!] WARNING: /dev/shm is missing on the host and could not be created."
    fi

    if [ ! -d /dev/dri ]; then
        echo "[!] WARNING: /dev/dri is missing on the host."
        echo "[!] Anland will fall back to software rendering."
    fi

    mkdir -p $DEBIAN_ROOTFS/etc/pulse $DEBIAN_ROOTFS/root \
             $DEBIAN_ROOTFS/usr/bin $DEBIAN_ROOTFS/var/lib/dbus \
             $DEBIAN_ROOTFS/usr/local/bin
    mkdir -p $TMPDIR/.X11-unix
    chmod 1777 $TMPDIR $TMPDIR/.X11-unix

    # -----------------------------------------------------------------
    # PulseAudio client config (chroot side)
    # The Anland helper starts pipewire-pulse inside the chroot and
    # points PULSE_SERVER at its socket.
    # -----------------------------------------------------------------
    cat << 'PULSECLIENT' > $DEBIAN_ROOTFS/etc/pulse/client.conf
# PulseAudio client config. PULSE_SERVER is set by the Anland helper.
# autospawn is disabled because the chroot has no standalone pulseaudio.
autospawn = no
daemon-binary = /bin/true
enable-shm = no
enable-memfd = no
PULSECLIENT
    chmod 644 $DEBIAN_ROOTFS/etc/pulse/client.conf

    # -----------------------------------------------------------------
    # Leftovers from the old Termux-proxy audio setup (<= 2.7.0).
    # An /etc/pipewire/client.conf replaces the packaged default
    # entirely, so an empty one leaves libpipewire clients (KWin's
    # Anland backend) without the native protocol module.
    # 99-termux-tunnel.conf loads a mandatory pulse-tunnel to the
    # Termux socket; when that socket is absent pipewire exits.
    # /usr/local/bin stubs (sleep-only) shadow the real daemons, so
    # the helper never sees the pipewire-0 socket.
    # -----------------------------------------------------------------
    rm -f $DEBIAN_ROOTFS/etc/pipewire/client.conf \
          $DEBIAN_ROOTFS/etc/pipewire/pipewire.conf.d/99-termux-tunnel.conf \
          $DEBIAN_ROOTFS/usr/local/bin/pipewire \
          $DEBIAN_ROOTFS/usr/local/bin/wireplumber \
          $DEBIAN_ROOTFS/usr/local/bin/pipewire-pulse \
          $DEBIAN_ROOTFS/root/.config/pulse/cookie 2>/dev/null

    if [ ! -f "$DEBIAN_ROOTFS/usr/bin/pm-is-supported" ]; then
        printf '#!/bin/sh\nexit 1\n' > $DEBIAN_ROOTFS/usr/bin/pm-is-supported
        chmod +x $DEBIAN_ROOTFS/usr/bin/pm-is-supported
    fi

    cat << 'ASOUND' > $DEBIAN_ROOTFS/etc/asound.conf
pcm.!default {
    type pulse
}
ctl.!default {
    type pulse
}
ASOUND

    rm -f $DEBIAN_ROOTFS/etc/resolv.conf
    echo 'nameserver 1.1.1.1' > $DEBIAN_ROOTFS/etc/resolv.conf

    echo "[*] Verifying and mounting necessary file systems..."
    mount_all

    rm -f $DEBIAN_ROOTFS/tmp/.X0-lock 2>/dev/null

    # -----------------------------------------------------------------
    # D-Bus services that can never start here: /data is mounted
    # nosuid, so dbus-daemon-launch-helper cannot run, and there is no
    # systemd. Their activation attempts only flood the log. Diverted
    # (not deleted), so package upgrades do not bring them back.
    # -----------------------------------------------------------------
    for _svc in \
        /usr/share/dbus-1/services/org.freedesktop.systemd1.service \
        /usr/share/dbus-1/system-services/org.freedesktop.systemd1.service \
        /usr/share/dbus-1/system-services/org.freedesktop.UDisks2.service \
        /usr/share/dbus-1/system-services/org.freedesktop.locale1.service \
        /usr/share/dbus-1/system-services/org.kde.localegenhelper.service; do
        [ -e "$DEBIAN_ROOTFS$_svc" ] || continue
        chroot "$DEBIAN_ROOTFS" /usr/bin/dpkg-divert --quiet --local --rename \
            --divert "$_svc.xiaoian-disabled" --add "$_svc" >/dev/null 2>&1 || true
    done

    # -----------------------------------------------------------------
    # KDE configuration (chroot side)
    # -----------------------------------------------------------------
    KDE_CFG_DIR="$DEBIAN_ROOTFS/root/.config"
    mkdir -p "$KDE_CFG_DIR"

    cat << 'BALOO' > "$KDE_CFG_DIR/baloofilerc"
[Basic Settings]
Indexing-Enabled=false
BALOO

    cat << 'KCONNECT' > "$KDE_CFG_DIR/kdeconnectrc"
[General]
AutoConnect=false
KCONNECT

    cat << 'KSCREENLOCK' > "$KDE_CFG_DIR/kscreenlockerrc"
[Daemon]
Autolock=false
LockOnResume=false
KSCREENLOCK

    cat << 'POWERDEVIL' > "$KDE_CFG_DIR/powermanagementprofilesrc"
[AC][DimDisplay]
idleTime=0

[AC][SuspendSession]
idleTime=0
suspendType=0

[AC][DPMSControl]
idleTime=0

[Battery][DimDisplay]
idleTime=0

[Battery][SuspendSession]
idleTime=0
suspendType=0

[Battery][DPMSControl]
idleTime=0
POWERDEVIL

    cp -f "$ANLAND_HELPER_LOCAL"  "$DEBIAN_ROOTFS/root/startplasma-anland.sh"
    chmod +x "$DEBIAN_ROOTFS/root/startplasma-anland.sh"

    # -----------------------------------------------------------------
    # setup-pkgs.sh (regenerated every time, but only run when needed)
    # -----------------------------------------------------------------
    cat << 'SETUP' > "$SETUP_SCRIPT"
#!/bin/bash
export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
export DEBIAN_FRONTEND=noninteractive
export DEBCONF_NONINTERACTIVE_SEEN=true
export APT_LISTCHANGES_FRONTEND=none

# plasma-workspace-wayland does NOT exist in trixie; Wayland support is
# inside plasma-workspace. XWayland runtime deps listed so apt can
# resolve the Anland XWayland .deb cleanly. elogind is intentionally
# omitted (causes dependency conflicts in chroot).
#
# pipewire + wireplumber + pipewire-pulse are REQUIRED: the Anland
# helper starts all three inside the chroot. KWin's Anland backend
# connects to pipewire and forwards speaker output and microphone
# input to/from the Android app. Apps reach it via pipewire-pulse.
# psmisc (killall) and sudo are used by the Anland helper.
REQUIRED_PKGS="plasma-desktop plasma-workspace \
kwin-wayland kwin-x11 libkwin6 kwin-common kwin-data \
xwayland xserver-common systemsettings dolphin konsole \
plasma-nm plasma-pa breeze breeze-icon-theme \
dbus-x11 dbus psmisc sudo \
libpulse0 libasound2-plugins alsa-utils \
pipewire wireplumber pipewire-pulse \
libdecor-0-0 libdrm2 libei1 libepoxy0 libgbm1 libgcrypt20 \
libgl1 liboeffis1 libpixman-1-0 libwayland-client0 libxcvt0 \
libxfont2 libxshmfence1 \
mesa-utils libgl1-mesa-dri libegl-mesa0 libglx-mesa0 mesa-libgallium \
libgles2 libegl1 \
vulkan-tools wget unzip tar xz-utils \
fonts-dejavu xfonts-base fonts-liberation \
locales ca-certificates firefox-esr ffmpeg libavcodec-extra"

# Helper: a package is considered installed if its dpkg status
# abbreviation is "ii " (install ok installed) or "hi " (hold ok
# installed). Using ${db:Status-Abbrev} avoids the "hold ok installed"
# mismatch of a plain ${Status} + grep.
pkg_truly_installed() {
    local abbrev
    abbrev=$(dpkg-query -W -f='${db:Status-Abbrev}' "$1" 2>/dev/null)
    case "$abbrev" in
        "ii "*|"hi "*) return 0 ;;
        *) return 1 ;;
    esac
}

MISSING_PKGS=""
for pkg in $REQUIRED_PKGS; do
    if ! pkg_truly_installed "$pkg"; then
        MISSING_PKGS="$MISSING_PKGS $pkg"
    fi
done

# Packages we do not want in the chroot:
# - baloo / akonadi / kdeconnect: unnecessary background indexers
# - pulseaudio: would conflict with pipewire-pulse
UNWANTED_PKGS="baloo-kf5 baloo-widgets akonadi-server akonadi-backend-sqlite \
kdeconnect kdeconnect-kde \
pulseaudio"

if [ -n "$MISSING_PKGS" ]; then
    echo "[*] Installing missing dependencies: $MISSING_PKGS"
    STAMP=/var/lib/apt/periodic/update-success-stamp
    if [ ! -f "$STAMP" ]; then
        apt-get update
    else
        AGE=$(( $(date +%s) - $(stat -c %Y "$STAMP") ))
        if [ "$AGE" -gt 86400 ]; then
            apt-get update
        fi
    fi
    if ! apt-get install -y --no-install-recommends \
            -o Dpkg::Options::="--force-confold" \
            -o Dpkg::Options::="--force-confdef" \
            $MISSING_PKGS; then
        echo "[!] ERROR: apt-get install failed. Aborting setup."
        exit 1
    fi
else
    echo "[*] All standard dependencies are satisfied."
fi

for pkg in $UNWANTED_PKGS; do
    if pkg_truly_installed "$pkg"; then
        echo "[*] Removing unwanted package: $pkg"
        apt-get purge -y "$pkg" >/dev/null 2>&1 || true
    else
        status=$(dpkg-query -W -f='${Status}' "$pkg" 2>/dev/null)
        if [ "$status" = "deinstall ok config-files" ]; then
            dpkg --purge "$pkg" >/dev/null 2>&1 || true
        fi
    fi
done
apt-get autoremove -y >/dev/null 2>&1 || true

rm -f /etc/xdg/autostart/baloo_file.desktop 2>/dev/null
rm -f /etc/xdg/autostart/akonadi_*.desktop 2>/dev/null
rm -f /etc/xdg/autostart/kdeconnect*.desktop 2>/dev/null
rm -f /etc/xdg/autostart/kalendarac.desktop 2>/dev/null

# Old audio stubs (<= 2.7.0) would shadow the real binaries.
rm -f /usr/local/bin/pipewire /usr/local/bin/wireplumber /usr/local/bin/pipewire-pulse

# Freedreno (KGSL) Mesa driver from staged tarball
FREEDRENO_TAR=$(ls /tmp/mesa-for-android-container*.tar.gz 2>/dev/null | head -1)
if [ -n "$FREEDRENO_TAR" ]; then
    echo "[*] Extracting Freedreno driver from $(basename "$FREEDRENO_TAR")..."
    rm -rf /tmp/freedreno-extract
    mkdir -p /tmp/freedreno-extract
    if tar -xzf "$FREEDRENO_TAR" -C /tmp/freedreno-extract 2>/dev/null; then
        DEB_FOUND=0
        for deb in $(find /tmp/freedreno-extract -name '*.deb' 2>/dev/null); do
            echo "[*] Installing $(basename "$deb")"
            apt-get install -y --reinstall "$deb" 2>/dev/null \
                || dpkg -i "$deb" \
                || apt-get install -f -y
            DEB_FOUND=1
        done
        if [ "$DEB_FOUND" = "0" ]; then
            echo "[*] No .deb inside; extracting Freedreno files into /usr ..."
            for sub in usr lib etc; do
                if [ -d "/tmp/freedreno-extract/$sub" ]; then
                    cp -a "/tmp/freedreno-extract/$sub/." "/$sub/"
                fi
            done
            ldconfig 2>/dev/null || true
        fi
        echo "[*] Freedreno driver installed."
    else
        echo "[!] Failed to extract $FREEDRENO_TAR."
    fi
    rm -rf /tmp/freedreno-extract
else
    echo "[!] No freedreno tarball found in /tmp."
fi

# --- XWayland ---
XWAYLAND_DEB=$(ls /tmp/xwayland_*.deb 2>/dev/null | head -1)
if [ -n "$XWAYLAND_DEB" ]; then
    echo "[*] Installing modified XWayland from $(basename "$XWAYLAND_DEB")..."
    apt-get install -y --reinstall "$XWAYLAND_DEB" 2>/dev/null \
        || dpkg -i "$XWAYLAND_DEB" \
        || apt-get install -f -y
    if pkg_truly_installed xwayland; then
        echo "[*] XWayland installed."
    else
        echo "[!] WARNING: XWayland installation did not complete cleanly."
    fi
else
    echo "[!] No xwayland_*.deb found in /tmp."
fi

# --- KWin Anland backend (ATOMIC INSTALL) ---
KWIN_ZIP=$(ls /tmp/kwin_anland-*.zip 2>/dev/null | head -1)
if [ -n "$KWIN_ZIP" ]; then
    echo "[*] Installing KWin Anland backend from $(basename "$KWIN_ZIP")..."
    rm -rf /tmp/kwin-debs
    mkdir -p /tmp/kwin-debs
    if unzip -o "$KWIN_ZIP" -d /tmp/kwin-debs >/dev/null 2>&1; then
        shopt -s nullglob
        KWIN_DEBS=(/tmp/kwin-debs/*.deb)
        shopt -u nullglob

        if [ "${#KWIN_DEBS[@]}" -gt 0 ]; then
            echo "[*] Found ${#KWIN_DEBS[@]} KWin package(s); installing atomically..."
            for _d in "${KWIN_DEBS[@]}"; do
                printf '      - %s\n' "$(basename "$_d")"
            done

            if ! apt-get install -y --reinstall "${KWIN_DEBS[@]}"; then
                echo "[!] apt transaction failed; retrying via dpkg all-at-once..."
                dpkg --force-architecture -i "${KWIN_DEBS[@]}" || true
                apt-get install -f -y || true
            fi

            for pkg in kwin-common kwin-data kwin-wayland kwin-x11 libkwin6 \
                       plasma-desktop plasma-workspace; do
                if ! pkg_truly_installed "$pkg"; then
                    echo "[!] $pkg missing after KWin install; reinstalling..."
                    apt-get install -y "$pkg" || true
                fi
            done

            STILL_MISSING=""
            for pkg in kwin-common kwin-data kwin-wayland libkwin6 \
                       plasma-desktop plasma-workspace; do
                pkg_truly_installed "$pkg" || STILL_MISSING="$STILL_MISSING $pkg"
            done
            if [ -n "$STILL_MISSING" ]; then
                echo "[!] ERROR: Critical packages still missing after repair:$STILL_MISSING"
                echo "[!] Aborting: a broken desktop stack would not start."
                exit 1
            fi
            echo "[*] KWin Anland backend installed."
        else
            echo "[!] No .deb files found inside $KWIN_ZIP."
        fi
    else
        echo "[!] Failed to unzip $KWIN_ZIP."
    fi
    rm -rf /tmp/kwin-debs
else
    echo "[!] No kwin_anland-*.zip found in /tmp."
fi

echo "[*] Marking critical packages as held..."
apt-mark hold \
    xwayland \
    libegl-mesa0 libgbm1 libgl1-mesa-dri libglx-mesa0 \
    mesa-libgallium mesa-vulkan-drivers \
    kwin-common kwin-data kwin-wayland libkwin6 \
    >/dev/null 2>&1 || true

rm -f /tmp/xwayland_*.deb \
      /tmp/kwin_anland-*.zip /tmp/mesa-for-android-container*.tar.gz

echo "[*] Debian setup complete."
SETUP

    chmod +x "$SETUP_SCRIPT"

    # -----------------------------------------------------------------
    # Fast pre-check (${db:Status-Abbrev} via absolute dpkg-query)
    # pipewire, wireplumber and pipewire-pulse are critical for audio.
    # -----------------------------------------------------------------
    CHROOT_SETUP_NEEDED=0

    for _p in kwin-common kwin-data kwin-wayland kwin-x11 libkwin6 \
              plasma-desktop plasma-workspace xwayland \
              pipewire wireplumber pipewire-pulse; do
        _abbrev=$(chroot "$DEBIAN_ROOTFS" /usr/bin/dpkg-query -W -f='${db:Status-Abbrev}' "$_p" 2>/dev/null)
        case "$_abbrev" in
            "ii "*|"hi "*) : ;;
            *)
                echo "[*] Critical package '$_p' not installed (status: '$_abbrev'); setup required."
                CHROOT_SETUP_NEEDED=1
                break
                ;;
        esac
    done

    if [ "$CHROOT_SETUP_NEEDED" = "0" ]; then
        for _f in "$XWAYLAND_DEB" "$KWIN_ZIP" "$FREEDRENO_TAR"; do
            [ -f "$_f" ] || continue
            _marker="$DEBIAN_ROOTFS/.component-$(basename "$_f").installed"
            if [ ! -f "$_marker" ] || [ "$_f" -nt "$_marker" ]; then
                echo "[*] Component $(basename "$_f") is new or updated; setup required."
                CHROOT_SETUP_NEEDED=1
                break
            fi
        done
    fi

    if [ "$CHROOT_SETUP_NEEDED" = "1" ]; then
        # The large component files are only copied when setup runs.
        echo "[*] Staging component files into chroot /tmp..."
        cp -f "$XWAYLAND_DEB"  "$DEBIAN_ROOTFS/tmp/"
        cp -f "$KWIN_ZIP"      "$DEBIAN_ROOTFS/tmp/"
        cp -f "$FREEDRENO_TAR" "$DEBIAN_ROOTFS/tmp/"
        echo "[*] Running Debian package setup..."
        chroot "$DEBIAN_ROOTFS" /bin/bash /setup-pkgs.sh || {
            echo "[!] ERROR: Debian setup failed. Aborting."
            exit 1
        }
        for _f in "$XWAYLAND_DEB" "$KWIN_ZIP" "$FREEDRENO_TAR"; do
            [ -f "$_f" ] && touch "$DEBIAN_ROOTFS/.component-$(basename "$_f").installed"
        done
    else
        echo "[*] All critical packages present and components unchanged; skipping setup."
    fi

    start_anland_daemon || {
        echo "[!] WARNING: Anland daemon did not come up cleanly."
        echo "[!] Continuing anyway; KWin will likely fail to connect."
    }

    # Audio runs entirely inside the chroot (started by the Anland
    # helper); stale Termux-side sockets from <= 2.7.0 are removed.
    rm -f "$TMPDIR/pipewire-0" "$TMPDIR/pipewire-0.lock" "$TMPDIR/pulse/native" 2>/dev/null

    if [ "$MODE" = "mirror" ]; then
        echo "[*] Applying external size for mirror mode..."
        apply_external_size || echo "[!] Skipping external size (no display)."
    else
        echo "[*] $MODE mode: skipping wm size/density."
    fi

    if [ "$MODE" = "extend" ]; then
        echo "[*] Launching Anland app on external display (displayId=$EXTERNAL_DISPLAY_ID)..."
        /system/bin/am start -n "$ANLAND_APP_PACKAGE"/.MainActivity \
            --display "$EXTERNAL_DISPLAY_ID" \
            --windowingMode 1 -f 0x18000000
    else
        echo "[*] Launching Anland Android frontend application..."
        /system/bin/am start -n "$ANLAND_APP_PACKAGE"/.MainActivity
    fi
    # Wait for the app process instead of a fixed 3s, then give its
    # activity a moment to attach to the display daemon.
    i=0
    while ! pidof "$ANLAND_APP_PACKAGE" >/dev/null 2>&1 && [ $i -lt 25 ]; do
        sleep 0.2
        i=$((i+1))
    done
    sleep 1

    # -----------------------------------------------------------------
    # Session script
    # -----------------------------------------------------------------
    cat << 'SESSION' > "$SESSION_SCRIPT"
#!/bin/bash
export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
export HOME=/root
export TMPDIR=/tmp
export TEMP=/tmp
export TMP=/tmp
export XDG_RUNTIME_DIR=/run/user/0
export ICEAUTHORITY=/root/.ICEauthority
export LANG=en_US.UTF-8
export LC_ALL=en_US.UTF-8
export MOZ_ALLOW_RUN_AS_ROOT=1
export MOZ_ALLOW_RUN_AS_ROOT_CONFIRM=1
export NO_AT_BRIDGE=1

# Silence Qt log categories that only report services a chroot cannot
# provide (UDisks2, ModemManager, X11-only window-system calls).
export QT_LOGGING_RULES="kf.solid.backends.udisks2=false;kf.modemmanagerqt=false;kf.windowsystem=false"

# Audio: the Anland helper starts pipewire, wireplumber and
# pipewire-pulse and sets PULSE_SERVER itself. Set
# ANLAND_AUDIO_DEBUG=1 for verbose logs in /tmp/anland/*.log.

# Hardware-specific GPU env.
export MESA_LOADER_DRIVER_OVERRIDE=kgsl
export TURNIP_KMD=kgsl
export GALLIUM_DRIVER=freedreno
export FD_FORCE_KGSL=1
export XWAYLAND_FORCE_KGSL_SURFACELESS=1

mkdir -p /var/lib/dbus
[ -s /var/lib/dbus/machine-id ] || dbus-uuidgen --ensure > /var/lib/dbus/machine-id 2>/dev/null

mkdir -p "$XDG_RUNTIME_DIR" /tmp/anland
chmod 0700 "$XDG_RUNTIME_DIR"
chmod 777 /tmp/anland

# Start system D-Bus if not already running. plasmashell's session
# backend, polkit and udisks expect a system bus.
if ! dbus-send --system --print-reply --dest=org.freedesktop.DBus / \
        org.freedesktop.DBus.Peer.Ping >/dev/null 2>&1; then
    rm -f /run/dbus/pid /run/dbus/system_bus_socket 2>/dev/null
    mkdir -p /run/dbus
    dbus-daemon --system --fork </dev/null >/dev/null 2>&1
fi

if [ -x /root/startplasma-anland.sh ]; then
    exec dbus-run-session -- /root/startplasma-anland.sh
else
    echo "[!] ERROR: /root/startplasma-anland.sh missing or not executable."
    exit 1
fi
SESSION

    chmod +x "$SESSION_SCRIPT"

    rotate_log

    # -----------------------------------------------------------------
    # Session wrapper: runs the chroot session, a plasmashell watchdog,
    # and on exit the shared teardown from $LIB_FILE.
    # -----------------------------------------------------------------
    cat << WRAPPER > "$SESSION_WRAPPER"
#!/system/bin/sh
. "$LIB_FILE"
LOG_FILE="$LOG_FILE"

cleanup() {
    RC=\${1:-\$?}
    trap - EXIT TERM INT HUP
    teardown_session >> "\$LOG_FILE" 2>&1
    rm -f "\$TMPDIR/anland/display_daemon.sock" 2>/dev/null
    exit "\$RC"
}

trap 'cleanup \$?' EXIT
trap 'cleanup 143' TERM
trap 'cleanup 130' INT
trap 'cleanup 129' HUP

chroot "\$DEBIAN_ROOTFS" /usr/bin/env -i \
    HOME=/root \
    PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
    TERM=xterm-256color \
    /bin/bash /start-kde.sh < /dev/null >> "\$LOG_FILE" 2>&1 &
CHROOT_PID=\$!

# Watchdog: armed only after plasmashell has appeared once, so a slow
# startup is never mistaken for a logout. After that, ~3s without
# plasmashell (logout) ends the session.
(
    SEEN=0
    GONE=0
    while kill -0 "\$CHROOT_PID" 2>/dev/null; do
        if pidof plasmashell >/dev/null 2>&1; then
            SEEN=1
            GONE=0
        elif [ "\$SEEN" = "1" ]; then
            GONE=\$((GONE + 1))
            if [ "\$GONE" -ge 3 ]; then
                echo "[*] Watchdog: plasmashell gone for \${GONE}s; terminating session." >> "\$LOG_FILE"
                kill -TERM "\$CHROOT_PID" 2>/dev/null
                n=0
                while kill -0 "\$CHROOT_PID" 2>/dev/null && [ "\$n" -lt 10 ]; do
                    sleep 0.2
                    n=\$((n + 1))
                done
                kill -KILL "\$CHROOT_PID" 2>/dev/null
                break
            fi
        fi
        sleep 1
    done
) &
WATCHDOG_PID=\$!

wait "\$CHROOT_PID" 2>/dev/null
RC=\$?

kill "\$WATCHDOG_PID" 2>/dev/null
wait "\$WATCHDOG_PID" 2>/dev/null

exit \$RC
WRAPPER

    chmod +x "$SESSION_WRAPPER"

    echo "$MODE" > "$MODE_FILE"
    chmod 0644 "$MODE_FILE"

    echo ""
    echo "[*] Booting KDE Plasma 6 (Wayland) on Anland backend..."
    echo "[*] Detailed logs: $LOG_FILE (previous run: $LOG_FILE.1)"

    if command -v setsid >/dev/null 2>&1; then
        setsid "$SESSION_WRAPPER" </dev/null >/dev/null 2>&1 &
    else
        nohup "$SESSION_WRAPPER" </dev/null >/dev/null 2>&1 &
    fi
    WRAPPER_PID=$!
    write_state "$WRAPPER_PID"

    if wait_for_session "$WRAPPER_PID"; then
        echo ""
        echo "[*] Xiaoian is running ($MODE mode)."
        echo "[*] Session PID: $WRAPPER_PID (saved to $STATE_FILE)"
        echo "[*] You can safely close Termux now."
        echo ""

        if [ "$MODE" = "local" ]; then
            echo "[*]   $0 -t          -> stop the system"
            echo "[*]   $0 -u          -> delete everything"
        else
            echo "[*]   $0 -k          -> virtual lock (press POWER to unlock)"
            if [ "$MODE" = "mirror" ]; then
                echo "[*]   $0 -e          -> resize to external"
                echo "[*]   $0 -i          -> restore native size"
            fi
            echo "[*]   $0 -t          -> stop the system"
            echo "[*]   $0 -u          -> delete everything"
        fi
    else
        echo "[!] Session failed to start. Check $LOG_FILE"
        rm -f "$STATE_FILE" "$MODE_FILE"
        exit 1
    fi
}

# =====================================================================
# 11. DISPATCH
# =====================================================================
[ "$ACTION" != "version" ] && reset_stale_state

case "$ACTION" in
    version)   do_version ;;
    stop)      do_stop ;;
    uninstall) do_uninstall ;;
    lock)      do_lock ;;
    exsize)    do_exsize ;;
    insize)    do_insize ;;
    start)     do_start ;;
esac