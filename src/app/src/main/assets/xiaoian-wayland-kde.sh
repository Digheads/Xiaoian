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
SCRIPT_VERSION="2.10.0"
SCRIPT_DATE="2026-09-18"
SCRIPT_AUTHOR="Digheads Ferke"

export PATH="/system/bin:/system/xbin:$PATH"
export TMPDIR="/data/data/com.xiaoian.app/files/tmp"
export XDG_RUNTIME_DIR="$TMPDIR"

: "${HOME:=/data/local/tmp}"
export HOME


INFRA_ROOT="/data/local/xiaoian-wayland-kde"
LIB_FILE="$INFRA_ROOT/lib.sh"
LOG_FILE="$INFRA_ROOT/de_debug.log"
# Downloaded components (mesa, xwayland, kwin, helper scripts) live in the
# app's own storage next to the rootfs tarball, not under the infra root:
# both desktops pull the same assets, so sharing them halves the downloads,
# and uninstalling one desktop no longer throws away the other's files.
APP_DATA_DIR="/data/data/com.xiaoian.app"
INSTALLER_DIR="$APP_DATA_DIR/files/downloads"

# Update checks against GitHub happen at most this often (seconds).
UPDATE_CHECK_INTERVAL=86400

ANLAND_REPO="lfdevs/anland-termux"
MESA_REPO="lfdevs/mesa-for-android-container"
ANLAND_HELPER_URL="https://raw.githubusercontent.com/${ANLAND_REPO}/main/scripts/startplasma-anland.sh"

ANLAND_APP_PACKAGE="com.xiaoian.app"

# The display daemon ships inside the APK as libanland.so: the packager
# extracts lib*.so into the app's native library directory with execute
# permission, which /data/data is denied (W^X). The app resolves that path
# and passes it in as $ANLAND_BIN -- this script is only ever started by the
# app, so it does not go looking for it.

# Socket the daemon listens on. The path is not ours to choose: the
# upstream startplasma-anland.sh hard-codes
#   export ANLAND_SOCKET=/tmp/anland/display_daemon.sock
# in start_container() before launching KWin, overwriting anything we
# export, so the daemon has to be where KWin will look. $TMPDIR is
# bind-mounted onto the chroot's /tmp (section 3), which makes the host
# path below and that chroot path the same socket. The Android consumer
# opens the host path; keep MainActivity.DEFAULT_SOCKET_PATH in step.
ANLAND_SOCKET="$TMPDIR/anland/display_daemon.sock"

# Desktop-specific values consumed by the common library (section 3).
DE_APP_PACKAGE="$ANLAND_APP_PACKAGE"
DE_SESSION_PROC="kwin_wayland"
DE_HOST_NAMES=$(basename "${ANLAND_BIN:-libanland.so}")
# Anchored: the daemon's command line starts with this path, our own
# starts with "su"/"sh" and merely contains it as an env assignment.
DE_HOST_PATTERNS="${ANLAND_BIN:+^$ANLAND_BIN}"

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
Anland daemon: libanland.so, shipped inside the Xiaoian APK.
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

# $TMPDIR has to exist before anything writes there -- http_cat uses it for
# its scratch file, well before the daemon start would create it.
mkdir -p "$INFRA_ROOT" "$TMPDIR" 2>/dev/null

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

# This shell, and every process that led to it. `pkill -f` matches against
# full command lines, and the app starts this script as
#   su -c 'ANLAND_BIN=<path> ... xiaoian-*.sh -s --local'
# so anything named on our own command line -- $ANLAND_BIN above -- also
# matches the shell doing the matching. Killing that is killing ourselves.
self_and_ancestors() {
    local p="$$" out=""
    while [ -n "$p" ] && [ "$p" != "0" ] && [ "$p" != "1" ]; do
        out="$out $p"
        p=$(grep -m1 '^PPid:' "/proc/$p/status" 2>/dev/null | tr -dc '0-9')
    done
    echo "$out"
}

# Host-side helpers of the desktop: exact process names plus anchored
# cmdline patterns, never a bare word. Both guards matter -- the anchor
# keeps the pattern off our own command line, the ancestor check makes a
# future unanchored pattern survivable instead of fatal.
stop_host_procs() {
    local sig="$1" n p pid skip
    skip=" $(self_and_ancestors) "
    for n in $DE_HOST_NAMES; do killall -"$sig" "$n" 2>/dev/null; done
    for p in $DE_HOST_PATTERNS; do
        for pid in $(pgrep -f "$p" 2>/dev/null); do
            case "$skip" in
                *" $pid "*)
                    echo "[!] Refusing to $sig own process $pid (pattern: $p)"
                    continue
                    ;;
            esac
            kill -"$sig" "$pid" 2>/dev/null
        done
    done
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
# ---- in-app terminals ------------------------------------------------
# A chroot terminal can be opened before its desktop, in which case it
# mounts the chroot itself and keeps a root shell alive in there. The
# start path below clears the chroot out before it begins, which used to
# kill exactly that shell and unmount from under it. The app writes the
# session ids of its live terminals to $XIAOIAN_OPEN_SESSIONS (see
# ScriptEnv.kt and TerminalSessions.rememberOpen), so they can be told
# apart from a crashed session's leftovers.
#
# Only the start path spares them. Teardown still kills everything in the
# chroot: by then the app has already closed its terminals, and anything
# left would keep the mounts busy.
app_terminal_sids() {
    [ -n "$XIAOIAN_OPEN_SESSIONS" ] && [ -f "$XIAOIAN_OPEN_SESSIONS" ] || return 0
    cat "$XIAOIAN_OPEN_SESSIONS" 2>/dev/null
}

# Chroot pids split by owner: "app" for the in-app terminals, "foreign"
# for everything else. Field 4 after the comm in /proc/N/stat is the
# session id -- the same field TerminalSessions and RootPty use, and the
# reason ptyspawn calls setsid().
chroot_pids_owned_by() {
    local want="$1" sids pid rest
    # The bare $(...) word-splits on the newlines, echo rejoins with
    # spaces, so the case below can match " <sid> " on either side.
    sids=" $(echo $(app_terminal_sids)) "
    for pid in $(find_chroot_pids); do
        rest=$(cat "/proc/$pid/stat" 2>/dev/null) || continue
        rest=${rest##*\) }
        set -- $rest
        case "$sids" in
            *" $4 "*) [ "$want" = app ] && echo "$pid" ;;
            *)        [ "$want" = foreign ] && echo "$pid" ;;
        esac
    done
}

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

app_apk_path() {
    pm path "$ANLAND_APP_PACKAGE" 2>/dev/null | sed -n '1s/^package://p'
}

# ---- progress protocol ---------------------------------------------------
# Machine-readable lines for the app, next to the human "[*]" lines:
#   @@PLAN <id> <title>   (all steps of this run, announced up front)
#   @@STEP <id>   @@PROGRESS <id> <done bytes> <total bytes|-1>   @@DONE <id>
# The app's Fetch and Cat tools print @@PROGRESS themselves when
# XIAOIAN_PROGRESS_ID is set. Identical to the XFCE script.
step_plan() { echo "@@PLAN $1 $2"; }
step()      { echo "@@STEP $1"; }
step_done() { echo "@@DONE $1"; }

# Usage: http_get <timeout s> <url> <output file>
# Downloads with the app's own HTTPS client, started through app_process.
# Running the download inside the tool rootfs is not an option: chroot
# hides the host paths it has to write to.
http_get() {
    local t="$1" url="$2" out="$3" pid="$4" apk
    apk=$(app_apk_path)
    [ -n "$apk" ] || { echo "[!] ERROR: cannot locate the $ANLAND_APP_PACKAGE APK." >&2; return 1; }
    CLASSPATH="$apk" XIAOIAN_PROGRESS_ID="$pid" timeout "$t" \
        app_process /system/bin com.xiaoian.app.tools.Fetch "$url" "$out"
}

# Usage: http_cat <timeout s> <url>  (body on stdout)
# Goes through a temp file, so a failed try never leaves partial output
# in the caller's pipe.
http_cat() {
    local tmp="$TMPDIR/.http_cat.$$"
    if http_get "$1" "$2" "$tmp"; then
        cat "$tmp"
        rm -f "$tmp"
        return 0
    fi
    rm -f "$tmp"
    return 1
}

# Usage: extract_txz <tarball> <destination dir> <progress id>
# The app's Cat tool decompresses the xz stream and feeds plain tar into
# Android's own tar. Nothing outside the APK is involved, on purpose: toybox
# tar has no xz support, and the only xz-capable tar on a rooted phone is the
# busybox that the root solution happens to ship. This used to look for it at
# /data/adb/magisk, /data/adb/ksu and /data/adb/ap -- three hard-coded paths
# that make the script care which root solution is installed, and that the
# next one would defeat. The app carries its own XZ decoder anyway, for the
# tool rootfs.
# Cat reports the bytes read on fd 3 (the script's stdout); the compressed
# size is the known total, so the app gets a real percentage without a second
# xz pass.
extract_txz() {
    local f="$1" dest="$2" pid="$3" apk rc
    echo "[*] Source:    $f ($(du -h "$f" 2>/dev/null | cut -f1))"
    echo "[*] Target:    $dest ($(df -h "$dest" 2>/dev/null | tail -1 | awk '{print $4}') free)"

    apk=$(app_apk_path)
    if [ -z "$apk" ]; then
        echo "[!] ERROR: cannot locate the $ANLAND_APP_PACKAGE APK, so the"
        echo "[!]        xz archive cannot be decompressed."
        return 1
    fi
    { CLASSPATH="$apk" XIAOIAN_PROGRESS_ID="$pid" \
        app_process /system/bin com.xiaoian.app.tools.Cat --xz "$f" 2>&3 \
        | tar -xf - -C "$dest"; } 3>&1
    rc=$?
    if [ "$rc" != "0" ]; then
        echo "[!] ERROR: the extraction pipeline exited with status $rc."
        echo "[!]   into: $dest"
        df -h "$dest" 2>&1 | sed 's/^/[!]   /'
    fi
    return $rc
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
    local repo="$1" pattern="$2" friendly="$3" old_glob="$4" pid="$5"
    local slug stamp cached api json matches url fname target f api_ok
    slug=$(printf '%s' "$friendly" | tr -c 'A-Za-z0-9' '_')
    stamp="$INSTALLER_DIR/.checked-$slug"
    cached=$(ls -t "$INSTALLER_DIR" 2>/dev/null | grep -E "^${pattern}\$" | head -1)

    if [ -n "$cached" ] && is_fresh "$stamp"; then
        echo "[*] $friendly: $cached (cached, checked recently)"
        return 0
    fi

    url=""
    api_ok=0
    for api in "releases/latest" "releases?per_page=20"; do
        json=$(http_cat 20 "https://api.github.com/repos/${repo}/${api}" 2>/dev/null)
        [ -n "$json" ] || continue
        api_ok=1
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
        if [ "$api_ok" = "0" ]; then
            # Nothing came back at all: the lookup failed, the pattern never
            # got a chance. Telling these two apart is the difference between
            # "upstream renamed the file" and "the downloader is broken".
            echo "[!] $friendly: no response from the GitHub API."
            echo "[!] The downloader writes its scratch file to \$TMPDIR ($TMPDIR);"
            echo "[!] check that it exists and that the device is online."
        else
            echo "[!] No matching asset for $friendly (pattern: $pattern) and nothing cached."
            echo "[!] The API answered, so the release exists but has no file"
            echo "[!] matching that pattern -- upstream probably renamed it."
        fi
        return 1
    fi

    fname=${url##*/}
    target="$INSTALLER_DIR/$fname"
    if [ -s "$target" ]; then
        echo "[*] $friendly: $fname (up to date)"
    else
        echo "[*] Downloading $friendly: $fname"
        rm -f "$target.part"
        if ! http_get 900 "$url" "$target.part" "$pid" \
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
    # This is the app's private storage and the app writes the rootfs tarball
    # here, so the directory has to stay owned by the app uid even though root
    # is what creates the files in it.
    _uid=$(stat -c %u "$APP_DATA_DIR" 2>/dev/null)
    [ -n "$_uid" ] && chown "$_uid:$_uid" "$INSTALLER_DIR" 2>/dev/null
    unset _uid
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
# 3c. ANLAND DAEMON (HOST SIDE)
# =====================================================================
check_anland_daemon() {
    if [ -z "$ANLAND_BIN" ]; then
        echo ""
        echo "[!] ================================================================"
        echo "[!] ANLAND_BIN is not set."
        echo "[!] The app passes it in when it starts this script; the script"
        echo "[!] does not resolve it itself. See ScriptEnv.kt."
        echo "[!] ================================================================"
        return 1
    fi
    if [ ! -x "$ANLAND_BIN" ]; then
        echo ""
        echo "[!] ================================================================"
        echo "[!] Anland display daemon not executable:"
        echo "[!]   $ANLAND_BIN"
        echo "[!]"
        echo "[!] Either the APK was built without"
        echo "[!]   anland/src/main/jniLibs/arm64-v8a/libanland.so"
        echo "[!] or the app module is missing useLegacyPackaging = true, which"
        echo "[!] is what gets lib*.so extracted with the execute bit set."
        echo "[!] ================================================================"
        return 1
    fi
    echo "[*] Anland daemon: $ANLAND_BIN"
    return 0
}

start_anland_daemon() {
    echo "[*] Starting Anland display daemon (host side)..."

    _proc=$(basename "$ANLAND_BIN")
    stop_host_procs KILL
    i=0
    while pidof "$_proc" >/dev/null 2>&1 && [ $i -lt 20 ]; do
        sleep 0.1
        i=$((i+1))
    done
    rm -f "$ANLAND_SOCKET" 2>/dev/null

    # The daemon creates the socket, but not the directory holding it, and
    # the chroot reaches it through the same bind mount.
    mkdir -p "$TMPDIR"
    chmod 777 "$TMPDIR"

    # Log to the session log: the daemon reports bind and client errors on
    # stderr, and they are the only clue when KWin cannot connect.
    setsid "$ANLAND_BIN" --socket "$ANLAND_SOCKET" >> "$LOG_FILE" 2>&1 &
    ANLAND_PID=$!
    echo "[*] Anland daemon pid: $ANLAND_PID ($_proc)"

    echo "[*] Waiting for display daemon socket at $ANLAND_SOCKET..."
    i=0
    while [ $i -lt 150 ]; do
        [ -S "$ANLAND_SOCKET" ] && break
        if ! kill -0 "$ANLAND_PID" 2>/dev/null; then
            echo "[!] Anland daemon exited prematurely."
            break
        fi
        i=$((i+1))
        sleep 0.2
    done

    if [ -S "$ANLAND_SOCKET" ]; then
        # The consumer runs as the app uid, the daemon as root.
        chmod 666 "$ANLAND_SOCKET" 2>/dev/null
        echo "[*] Anland display daemon socket is up (took $((i/5))s)."
        return 0
    else
        echo "[!] WARNING: Anland socket not found after 30s."
        echo "[!]   tail -50 $LOG_FILE"
        echo "[!]   ls -la $TMPDIR/"
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
    echo "Anland:  $ANLAND_BIN (shipped in the APK)"
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
    rm -f "$SESSION_WRAPPER" "$ANLAND_SOCKET"

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
        echo "[*] $INFRA_ROOT has been removed."
        echo "[*] Downloaded components in $INSTALLER_DIR were kept;"
        echo "[*] they are shared with the other desktop."
        echo "[*] NOTE: the Anland daemon lives inside the Xiaoian APK;"
        echo "[*]       uninstalling the app removes it."
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

    step_plan settings   "Checking display settings"
    step_plan components "Upstream components"
    if [ ! -f "$DEBIAN_ROOTFS/bin/bash" ]; then
        step_plan rootfs-extract "Extracting Debian rootfs"
    fi
    step_plan packages "Debian packages"
    step_plan anland   "Display daemon"
    step_plan session  "KDE Plasma desktop"

    step settings
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

    step_done settings

    ensure_installer_dir
    echo ""
    echo "[*] Root:      $INFRA_ROOT"
    echo "[*] Installer: $INSTALLER_DIR"

    check_anland_daemon || exit 1

    step components
    echo "[*] Checking upstream components..."
    fetch_asset "$ANLAND_REPO" "xwayland_24\\.1\\.6-91_arm64\\.deb" \
        "XWayland" "xwayland_*.deb" components || exit 1
    fetch_asset "$ANLAND_REPO" "kwin_anland-[^/]*debian[^/]*\\.zip" \
        "KWin Anland backend" "kwin_anland-*.zip" components || exit 1
    fetch_asset "$MESA_REPO" "mesa-for-android-container_[^/]*_debian_trixie_arm64\\.tar\\.gz" \
        "Freedreno driver" "mesa-for-android-container*.tar.gz" components || exit 1

    # The helper is refreshed together with the other components, so it
    # never drifts away from the KWin backend it belongs to.
    ANLAND_HELPER_LOCAL="$INSTALLER_DIR/startplasma-anland.sh"
    HELPER_STAMP="$INSTALLER_DIR/.checked-anland-helper"
    if [ -s "$ANLAND_HELPER_LOCAL" ] && is_fresh "$HELPER_STAMP"; then
        echo "[*] startplasma-anland.sh: cached, checked recently"
    else
        echo "[*] Checking startplasma-anland.sh..."
        rm -f "$ANLAND_HELPER_LOCAL.part"
        if http_get 60 "$ANLAND_HELPER_URL" "$ANLAND_HELPER_LOCAL.part" components \
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
    step_done components

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

        # The app downloads this and tells us where it put it. Hard-coding the
        # path in two places is how they end up disagreeing.
        ROOTFS_TARBALL="${XIAOIAN_ROOTFS_TARBALL:-$INSTALLER_DIR/rootfs-arm64.tar.xz}"
        
        if [ ! -s "$ROOTFS_TARBALL" ]; then
            echo "[!] ERROR: Rootfs tarball not found at"
            echo "[!]   $ROOTFS_TARBALL"
            echo "[!] The app downloads it before starting a session."
            echo "[!] Contents of $INSTALLER_DIR:"
            ls -la "$INSTALLER_DIR" 2>&1 | sed 's/^/[!]   /'
            exit 1
        fi

        step rootfs-extract
        echo "[*] Extracting rootfs (this will take a few minutes)..."
        extract_txz "$ROOTFS_TARBALL" "$DEBIAN_ROOTFS" rootfs-extract \
            || { echo "[!] ERROR: Extraction failed!"; exit 1; }
        echo "[*] Debian rootfs installation successfully completed!"
        step_done rootfs-extract
    fi

    # mount_all below is idempotent, so a chroot an in-app terminal has
    # already set up needs nothing doing to it -- and unmount_all would kill
    # that terminal's shell on the way.
    if [ -n "$(chroot_pids_owned_by app)" ] && [ -z "$(chroot_pids_owned_by foreign)" ]; then
        echo "[*] In-app terminal(s) are using this chroot; keeping them and their mounts."
    else
        echo "[*] Removing previous session locks..."
        unmount_all
    fi
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

    # apt drops to the _apt user to download, and Android only lets
    # processes in the inet group (gid 3003) open a socket. Without these
    # entries every apt-get inside the chroot fails to reach the network,
    # which then shows up much later as a missing package.
    for _g in "aid_inet:x:3003:_apt" "aid_net_raw:x:3004:_apt"; do
        grep -q "^${_g%%:*}:" $DEBIAN_ROOTFS/etc/group 2>/dev/null \
            || echo "$_g" >> $DEBIAN_ROOTFS/etc/group
    done

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

# APT::Status-Fd=1 adds machine-readable "dlstatus:" / "pmstatus:"
# lines to stdout, which the app turns into the progress bar.

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
        apt-get -o APT::Status-Fd=1 update
    else
        AGE=$(( $(date +%s) - $(stat -c %Y "$STAMP") ))
        if [ "$AGE" -gt 86400 ]; then
            apt-get -o APT::Status-Fd=1 update
        fi
    fi
    if ! apt-get install -y --no-install-recommends \
            -o APT::Status-Fd=1 \
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
    step packages
    echo "[*] Checking Debian dependencies and GPU drivers..."
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
    step_done packages

    step anland
    start_anland_daemon || {
        echo "[!] WARNING: Anland daemon did not come up cleanly."
        echo "[!] Continuing anyway; KWin will likely fail to connect."
    }
    step_done anland

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
        /system/bin/am start -n "$ANLAND_APP_PACKAGE"/com.anland.termux.MainActivity \
            --display "$EXTERNAL_DISPLAY_ID" \
            --windowingMode 1 -f 0x18000000
    else
        echo "[*] Launching Anland Android frontend application..."
        /system/bin/am start -n "$ANLAND_APP_PACKAGE"/com.anland.termux.MainActivity
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
    rm -f "$ANLAND_SOCKET" 2>/dev/null
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
    step session
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
        step_done session
        echo ""
        echo "[*] Xiaoian is running ($MODE mode)."
        echo "[*] Session PID: $WRAPPER_PID (saved to $STATE_FILE)"
        echo "[*] The session keeps running in the background."
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