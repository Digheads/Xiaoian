#!/system/bin/sh

# =====================================================================
# xiaoian.sh — Debian chroot + XFCE4 + Termux:X11 launcher
# Version: 1.5.0
# Date:    2026-09-17
# Author:  Digheads Ferke
# =====================================================================

# =====================================================================
# 1. CONFIG
# =====================================================================
SCRIPT_VERSION="1.5.0"
SCRIPT_DATE="2026-09-17"
SCRIPT_AUTHOR="Digheads Ferke"

export PREFIX="/data/data/com.termux/files/usr"
export PATH="$PREFIX/bin:$PREFIX/bin/applets:/system/bin:/system/xbin:$PATH"
export TMPDIR="$PREFIX/tmp"
export XDG_RUNTIME_DIR="$TMPDIR"

TERMUX_UID=$(stat -c "%u" /data/data/com.termux/files/usr/bin/bash 2>/dev/null || echo 10422)

INFRA_ROOT="/data/local/xiaoian-x11-xfce"
LIB_FILE="$INFRA_ROOT/lib.sh"
LOG_FILE="$INFRA_ROOT/de_debug.log"
INSTALLER_DIR="$INFRA_ROOT/install_files"

# Update checks against GitHub happen at most this often (seconds).
UPDATE_CHECK_INTERVAL=86400

MESA_REPO="lfdevs/mesa-for-android-container"
MESA_ASSET_PATTERN="mesa-for-android-container_[^/]*_debian_trixie_arm64\\.tar\\.gz"

TERMUX_X11_PACKAGE="com.termux.x11"

# PulseAudio (Termux side) listens on a unix socket in Termux $TMPDIR,
# which the chroot sees under /tmp. No TCP port is opened.
PULSE_HOST_DIR="$TMPDIR/xiaoian-pulse"
PULSE_CHROOT_SOCKET="/tmp/xiaoian-pulse/native"

# Desktop-specific values consumed by the common library (section 3).
# termux-x11 renames its process to "termux-x11 com.termux.x11 <args>".
DE_APP_PACKAGE="$TERMUX_X11_PACKAGE"
DE_SESSION_PROC="xfce4-session"
DE_HOST_NAMES="pulseaudio"
DE_HOST_PATTERNS='^termux-x11.com\.termux\.x11 com\.termux\.x11\.Loader'

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

Infrastructure root: /data/local/xiaoian-x11-xfce
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

SESSION_SCRIPT="$DEBIAN_ROOTFS/start-xfce.sh"
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
    echo "Stack:   Debian chroot + XFCE4 + Termux:X11"
    echo "Root:    $INFRA_ROOT"
    echo "Installer: $INSTALLER_DIR"
    echo "Mesa source: $MESA_REPO"
    echo "Audio:   PulseAudio (Termux, unix socket $PULSE_HOST_DIR/native)"
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
            echo "[*] Ensure Termux:X11 on external display (id=$EXT_ID)..."
            /system/bin/am start --display "$EXT_ID" \
                -n com.termux.x11/com.termux.x11.MainActivity \
                --windowingMode 1 -f 0x18000000 2>/dev/null
        else
            echo "[!] No external display detected; Termux:X11 not relaunched."
        fi
    else
        echo "[*] Mirror mode: Termux:X11 stays on default display."
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
    echo "[*] Stopping Xiaoian (X11 + XFCE)..."

    stop_session_wrapper
    teardown_session
    rm -f "$SESSION_WRAPPER"
    rm -rf "$PULSE_HOST_DIR" 2>/dev/null

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

    echo "[*] Uninstalling Xiaoian (X11 + XFCE)..."

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
    echo "[*] Checking Freedreno (KGSL) Mesa driver..."

    fetch_asset "$MESA_REPO" "$MESA_ASSET_PATTERN" "Freedreno driver" \
        "mesa-for-android-container*.tar.gz" || exit 1

    LOCAL_MESA_TAR=""
    for f in "$INSTALLER_DIR"/mesa-for-android-container*.tar.gz; do
        [ -f "$f" ] && LOCAL_MESA_TAR="$f" && break
    done

    if [ -z "$LOCAL_MESA_TAR" ]; then
        echo "[!] ERROR: Freedreno driver still missing after download."
        exit 1
    fi
    echo "[*] Freedreno driver: $(basename "$LOCAL_MESA_TAR")"

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

    mkdir -p $DEBIAN_ROOTFS/etc/pulse $DEBIAN_ROOTFS/root \
             $DEBIAN_ROOTFS/usr/bin $DEBIAN_ROOTFS/var/lib/dbus
    mkdir -p $TMPDIR/.X11-unix
    chmod 1777 $TMPDIR $TMPDIR/.X11-unix

    if [ ! -f "$DEBIAN_ROOTFS/usr/bin/pm-is-supported" ]; then
        printf '#!/bin/sh\nexit 1\n' > $DEBIAN_ROOTFS/usr/bin/pm-is-supported
        chmod +x $DEBIAN_ROOTFS/usr/bin/pm-is-supported
    fi

    # ALSA and PulseAudio clients reach the Termux PulseAudio through its
    # unix socket (PULSE_HOST_DIR, seen as /tmp/... inside the chroot).
    cat << ASOUND > $DEBIAN_ROOTFS/etc/asound.conf
pcm.!default {
    type pulse
    server "unix:$PULSE_CHROOT_SOCKET"
}
ctl.!default {
    type pulse
    server "unix:$PULSE_CHROOT_SOCKET"
}
ASOUND

    cat << PULSECLIENT > $DEBIAN_ROOTFS/etc/pulse/client.conf
default-server = unix:$PULSE_CHROOT_SOCKET
autospawn = no
PULSECLIENT

    rm -f $DEBIAN_ROOTFS/etc/resolv.conf
    echo 'nameserver 1.1.1.1' > $DEBIAN_ROOTFS/etc/resolv.conf

    echo "[*] Verifying and mounting necessary file systems..."
    mount_all

    rm -f $DEBIAN_ROOTFS/tmp/.X0-lock 2>/dev/null

    XFWM_XML="$DEBIAN_ROOTFS/root/.config/xfce4/xfconf/xfce-perchannel-xml/xfwm4.xml"
    if [ ! -f "$XFWM_XML" ]; then
        echo "[*] Generating safe XFCE display profile (compositing off)..."
        mkdir -p "$(dirname "$XFWM_XML")"
        cat << 'XFWM' > "$XFWM_XML"
<?xml version="1.0" encoding="UTF-8"?>
<channel name="xfwm4" version="1.0">
  <property name="general" type="empty">
    <property name="use_compositing" type="bool" value="false"/>
  </property>
</channel>
XFWM
    else
        echo "[*] Existing XFCE window-manager config found; leaving it alone."
    fi

    echo "[*] Checking Debian dependencies and GPU drivers..."

    cat << 'SETUP' > "$SETUP_SCRIPT"
#!/bin/bash
export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
export DEBIAN_FRONTEND=noninteractive
export DEBCONF_NONINTERACTIVE_SEEN=true
export APT_LISTCHANGES_FRONTEND=none

REQUIRED_PKGS="xfce4 xfce4-terminal dbus-x11 dbus pulseaudio-utils libasound2-plugins alsa-utils libpulse0 mesa-utils libgl1-mesa-dri libegl-mesa0 x11-utils x11-xserver-utils x11-xkb-utils firefox-esr ffmpeg libavcodec-extra vulkan-tools wget unzip tar xz-utils xfonts-base fonts-liberation locales ca-certificates"

MISSING_PKGS=""
for pkg in $REQUIRED_PKGS; do
    if ! dpkg -s "$pkg" >/dev/null 2>&1; then
        MISSING_PKGS="$MISSING_PKGS $pkg"
    fi
done

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
    apt-get install -y --no-install-recommends \
        -o Dpkg::Options::="--force-confold" \
        -o Dpkg::Options::="--force-confdef" \
        $MISSING_PKGS
else
    echo "[*] All standard dependencies are satisfied."
fi

FREEDRENO_TAR=""
for f in /tmp/mesa-for-android-container*.tar.gz; do
    [ -f "$f" ] && FREEDRENO_TAR="$f" && break
done

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
SETUP

    chmod +x "$SETUP_SCRIPT"

    # -----------------------------------------------------------------
    # Run the package setup only when something is missing or the
    # Freedreno driver changed (it used to reinstall Mesa every start).
    # -----------------------------------------------------------------
    CHROOT_SETUP_NEEDED=0
    for _p in xfce4 xfce4-terminal dbus-x11 libpulse0 libasound2-plugins \
              libgl1-mesa-dri x11-xserver-utils firefox-esr; do
        _abbrev=$(chroot "$DEBIAN_ROOTFS" /usr/bin/dpkg-query -W -f='${db:Status-Abbrev}' "$_p" 2>/dev/null)
        case "$_abbrev" in
            "ii "*|"hi "*) : ;;
            *)
                echo "[*] Package '$_p' not installed (status: '$_abbrev'); setup required."
                CHROOT_SETUP_NEEDED=1
                break
                ;;
        esac
    done

    MESA_BASENAME=$(basename "$LOCAL_MESA_TAR")
    MESA_MARKER="$DEBIAN_ROOTFS/.component-$MESA_BASENAME.installed"
    if [ "$CHROOT_SETUP_NEEDED" = "0" ] && \
       { [ ! -f "$MESA_MARKER" ] || [ "$LOCAL_MESA_TAR" -nt "$MESA_MARKER" ]; }; then
        echo "[*] Freedreno driver $MESA_BASENAME is new or updated; setup required."
        CHROOT_SETUP_NEEDED=1
    fi

    if [ "$CHROOT_SETUP_NEEDED" = "1" ]; then
        echo "[*] Staging Freedreno driver into chroot /tmp as $MESA_BASENAME ..."
        cp -f "$LOCAL_MESA_TAR" "$DEBIAN_ROOTFS/tmp/$MESA_BASENAME"
        echo "[*] Running Debian package setup..."
        if chroot "$DEBIAN_ROOTFS" /bin/bash /setup-pkgs.sh; then
            touch "$MESA_MARKER"
        else
            echo "[!] WARNING: Debian setup reported errors; it will be retried on the next start."
        fi
        rm -f "$DEBIAN_ROOTFS/tmp/mesa-for-android-container"*.tar.gz 2>/dev/null
    else
        echo "[*] All packages present and Freedreno driver unchanged; skipping setup."
    fi

    # -----------------------------------------------------------------
    # PulseAudio (Termux side): unix socket only, no TCP port, so other
    # Android apps cannot connect to it.
    # -----------------------------------------------------------------
    echo "[*] Initializing PulseAudio sound server (in Termux, unix socket)..."
    rm -rf "$PULSE_HOST_DIR"
    mkdir -p "$PULSE_HOST_DIR"
    chown "$TERMUX_UID:$TERMUX_UID" "$PULSE_HOST_DIR"
    chmod 0755 "$PULSE_HOST_DIR"
    PA_ARGS="--start --exit-idle-time=-1 --load=module-sles-sink"
    PA_ARGS="$PA_ARGS --load='module-native-protocol-unix auth-anonymous=1 socket=$PULSE_HOST_DIR/native'"
    su "$TERMUX_UID" -c "env -i PATH=$PREFIX/bin TMPDIR=$TMPDIR $PREFIX/bin/pulseaudio $PA_ARGS </dev/null >/dev/null 2>&1"
    i=0
    while [ ! -S "$PULSE_HOST_DIR/native" ] && [ $i -lt 25 ]; do
        sleep 0.2
        i=$((i+1))
    done
    if [ -S "$PULSE_HOST_DIR/native" ]; then
        echo "[*] PulseAudio socket is up."
    else
        echo "[!] WARNING: PulseAudio socket not found; XFCE will have no sound."
        echo "[!] Check in Termux: pkg install pulseaudio"
    fi

    # -----------------------------------------------------------------
    # X server first, then the app: the socket is polled instead of a
    # fixed sleep after launching the app.
    # -----------------------------------------------------------------
    echo "[*] Starting Termux:X11 display server in background..."
    if command -v setsid >/dev/null 2>&1; then
        setsid $PREFIX/bin/termux-x11 :0 -ac -nolisten tcp </dev/null >/dev/null 2>&1 &
    else
        $PREFIX/bin/termux-x11 :0 -ac -nolisten tcp </dev/null >/dev/null 2>&1 &
    fi

    echo "[*] Waiting for X server socket at $DEBIAN_ROOTFS/tmp/.X11-unix/X0..."
    i=0
    while [ ! -S "$DEBIAN_ROOTFS/tmp/.X11-unix/X0" ] && [ $i -lt 100 ]; do
        sleep 0.2
        i=$((i+1))
    done
    if [ -S "$DEBIAN_ROOTFS/tmp/.X11-unix/X0" ]; then
        echo "[*] X server is up (took $((i/5))s)."
    else
        echo "[!] WARNING: X server socket never appeared after 20s."
        echo "[!] XFCE will likely fail with 'Cannot open display :0'."
    fi

    if [ "$MODE" = "mirror" ]; then
        echo "[*] Applying external size for mirror mode..."
        apply_external_size || echo "[!] Skipping external size (no display)."
    else
        echo "[*] $MODE mode: skipping wm size/density."
    fi

    if [ "$MODE" = "extend" ]; then
        echo "[*] Launching Termux:X11 on external display (displayId=$EXTERNAL_DISPLAY_ID)..."
        /system/bin/am start -n "$TERMUX_X11_PACKAGE"/com.termux.x11.MainActivity \
            --display "$EXTERNAL_DISPLAY_ID" --windowingMode 1 -f 0x18000000
    else
        echo "[*] Launching Termux:X11 Android frontend application..."
        /system/bin/am start -n "$TERMUX_X11_PACKAGE"/com.termux.x11.MainActivity
    fi

    cat << 'SESSION' > "$SESSION_SCRIPT"
#!/bin/bash
export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
export HOME=/root
export DISPLAY=:0
export TMPDIR=/tmp
export TEMP=/tmp
export TMP=/tmp
export XDG_RUNTIME_DIR=/run/user/0
export ICEAUTHORITY=/root/.ICEauthority
export PULSE_SERVER=@PULSE_SERVER@
export ALSA_CARD=default
export LANG=en_US.UTF-8
export LC_ALL=en_US.UTF-8
export MOZ_ALLOW_RUN_AS_ROOT=1
export MOZ_ALLOW_RUN_AS_ROOT_CONFIRM=1
export NO_AT_BRIDGE=1

mkdir -p /var/lib/dbus
[ -s /var/lib/dbus/machine-id ] || dbus-uuidgen --ensure > /var/lib/dbus/machine-id 2>/dev/null

if [ -f /usr/share/vulkan/icd.d/freedreno_icd.aarch64.json ]; then
    echo "[*] Freedreno/Turnip Vulkan driver detected. Activating native Zink acceleration!"
    export GALLIUM_DRIVER=zink
    export MESA_LOADER_DRIVER_OVERRIDE=zink
    export TU_DEBUG=noconform
    export ZINK_DESCRIPTORS=lazy
    export VK_ICD_FILENAMES=/usr/share/vulkan/icd.d/freedreno_icd.aarch64.json
else
    echo "[*] Freedreno not found. Falling back to safe software rendering (llvmpipe)."
    export LIBGL_ALWAYS_SOFTWARE=1
    export GALLIUM_DRIVER=llvmpipe
    export GSK_RENDERER=cairo
    export GDK_GL=disable
fi

mkdir -p "$XDG_RUNTIME_DIR" /run/dbus
chmod 0700 "$XDG_RUNTIME_DIR"

if ! dbus-send --system --print-reply --dest=org.freedesktop.DBus / \
        org.freedesktop.DBus.Peer.Ping >/dev/null 2>&1; then
    rm -f /run/dbus/pid /run/dbus/system_bus_socket 2>/dev/null
    mkdir -p /run/dbus
    dbus-daemon --system --fork </dev/null >/dev/null 2>&1
fi

if xdpyinfo -display :0 >/dev/null 2>&1; then
    setxkbmap hu 2>/dev/null
    xsetroot -solid "#2c3e50" 2>/dev/null
else
    echo "[!] WARNING: cannot reach display :0."
fi

export DISPLAY=:0
exec dbus-run-session -- xfce4-session
SESSION

    sed -i "s|@PULSE_SERVER@|unix:$PULSE_CHROOT_SOCKET|" "$SESSION_SCRIPT"
    chmod +x "$SESSION_SCRIPT"

    rotate_log

    # -----------------------------------------------------------------
    # Session wrapper: runs the chroot session and on exit (logout,
    # --stop, crash) the shared teardown from $LIB_FILE.
    # -----------------------------------------------------------------
    cat << WRAPPER > "$SESSION_WRAPPER"
#!/system/bin/sh
. "$LIB_FILE"
LOG_FILE="$LOG_FILE"

cleanup() {
    RC=\${1:-\$?}
    trap - EXIT TERM INT HUP
    teardown_session >> "\$LOG_FILE" 2>&1
    rm -rf "$PULSE_HOST_DIR" 2>/dev/null
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
    /bin/bash /start-xfce.sh < /dev/null >> "\$LOG_FILE" 2>&1 &
CHROOT_PID=\$!
wait "\$CHROOT_PID" 2>/dev/null
exit \$?
WRAPPER

    chmod +x "$SESSION_WRAPPER"

    echo "$MODE" > "$MODE_FILE"
    chmod 0644 "$MODE_FILE"

    echo ""
    echo "[*] Booting XFCE4 Desktop Environment in a detached session..."
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