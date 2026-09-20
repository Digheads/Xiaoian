package com.xiaoian.app.terminal

import android.content.Context
import com.xiaoian.app.service.BootstrapManager
import com.xiaoian.app.service.ScriptEnv

/**
 * The shell script each kind of session runs inside its pty.
 *
 * These run as root, from the one open [com.xiaoian.app.shell.RootShell], with
 * `libptyspawn.so` in front of them so the pty is a real controlling terminal.
 * They end in `exec`, so the process the app tracks is the shell the user is
 * typing at -- not a wrapper that would outlive it.
 */
object SessionScripts {

    /** Set on every session; the chroots have no useful environment of their own. */
    private const val PATH = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"

    /** Where the host filesystem shows up inside the local tool rootfs. */
    const val ANDROID_MOUNT = "mnt/android"

    /**
     * `ensure_mount LABEL TARGET <mount args...>`: mount unless it is already
     * there, and say so once, in our own words, if it did not work.
     *
     * toybox's own failure line ("mount: 'sys' -> '/data/...': Device or
     * resource busy") is the first thing a new session would print, and it is
     * alarming out of proportion -- the usual cause is a mount left behind by a
     * rootfs that was just replaced, and a moment later it succeeds. So:
     * swallow the first attempt's noise, try once more, and only complain if
     * the mount really is not there afterwards.
     */
    private val ENSURE_MOUNT = """
        ensure_mount() {
            _label="${'$'}1"; _target="${'$'}2"; shift 2
            grep -q " ${'$'}_target " /proc/mounts && return 0
            mount "${'$'}@" 2>/dev/null || { sleep 0.3; mount "${'$'}@" 2>/dev/null; }
            grep -q " ${'$'}_target " /proc/mounts && return 0
            echo "[!] could not mount ${'$'}_label at ${'$'}_target"
            return 1
        }
    """.trimIndent()

    /**
     * Unmounts everything at or under [target], deepest first, and succeeds
     * only if nothing is left.
     *
     * Two rules make this what it is, both learned on the device:
     *
     * - **Deepest first.** A parent cannot go before its children, and reverse
     *   lexicographic order happens to give exactly that.
     * - **`rslave` before every `umount`.** An unmount propagates to the peers
     *   of the mount's *parent*. For the ordinary binds that is harmless --
     *   their parent is the `/data` mount, and the host's `/dev` is not a child
     *   of `/data`. It is not harmless under [ANDROID_MOUNT], where the parent
     *   is a replica of `/` itself and the real `/` is its peer: unmounting the
     *   replicas there tears the host's `/system` and `/data` down with them,
     *   and the phone loses every binary until it is rebooted. Taking each
     *   mount out of its peer group first is what makes this safe.
     *
     * Wrapped in a subshell because callers run it inside the long-lived root
     * shell, where a bare `exit` would take that shell down.
     */
    fun unmountTree(target: String): String = """
        (
        t='$target'
        i=0
        while [ ${'$'}i -lt 8 ] && grep -q -e " ${'$'}t " -e " ${'$'}t/" /proc/mounts; do
            grep -e " ${'$'}t " -e " ${'$'}t/" /proc/mounts | cut -d' ' -f2 | sort -r |
            while read -r m; do
                mount -o rslave none "${'$'}m" 2>/dev/null
                umount "${'$'}m" 2>/dev/null || umount -l "${'$'}m" 2>/dev/null
            done
            i=${'$'}((i + 1))
        done
        ! grep -q -e " ${'$'}t " -e " ${'$'}t/" /proc/mounts
        )
    """.trimIndent()

    fun of(context: Context, spec: SessionSpec): String = when (spec) {
        is SessionSpec.Local -> local(BootstrapManager(context).prefixDir.absolutePath)
        is SessionSpec.DesktopChroot -> desktopChroot(spec.de)
    }

    /**
     * The tool rootfs is a plain Debian tree with nothing mounted into it, and
     * the shell would otherwise inherit Android's environment, whose PATH
     * points at directories that do not exist inside the chroot -- without
     * `env -i` every command comes back as "command not found". /proc, /sys and
     * /dev are what `ps`, `df` and anything writing to /dev/null need.
     *
     * The mounts are idempotent and shared with any later terminal, so they are
     * set up here but never torn down. They are torn down by
     * `BootstrapManager.wipeRootfs()` before the rootfs is deleted -- `rm -rf`
     * over a live `--bind /dev` would walk into it and delete the host's device
     * nodes, and toybox `rm` has no `--one-file-system`.
     */
    private fun local(rootfs: String): String = """
        R='$rootfs'
        if [ ! -x "${'$'}R/bin/bash" ]; then
            echo 'The Debian tool environment is not installed yet.'
            echo 'Start a desktop session once -- the app installs it on the way.'
            exit 1
        fi
        for m in proc sys dev dev/pts $ANDROID_MOUNT; do mkdir -p "${'$'}R/${'$'}m"; done

        $ENSURE_MOUNT

        ensure_mount proc "${'$'}R/proc" -t proc proc "${'$'}R/proc"
        ensure_mount sysfs "${'$'}R/sys" -t sysfs sys "${'$'}R/sys"
        ensure_mount /dev "${'$'}R/dev" --bind /dev "${'$'}R/dev"
        ensure_mount /dev/pts "${'$'}R/dev/pts" --bind /dev/pts "${'$'}R/dev/pts"

        $ANDROID_BIND

        exec chroot "${'$'}R" /usr/bin/env -i \
            HOME=/root \
            TERM=xterm-256color \
            PATH=$PATH \
            LANG=C.UTF-8 \
            /bin/bash -i
    """.trimIndent()

    /**
     * Binds the whole Android filesystem into the chroot at [ANDROID_MOUNT].
     *
     * Three steps, and each one is there for a reason found on the device.
     *
     * 1. **A mount point of its own, made private first.** A new mount
     *    propagates to the peers of the mount that contains it. The app runs in
     *    its own mount namespace, `/data` is shared with the global one, so a
     *    plain `rbind` here put ~190 entries into *both* namespaces, plus
     *    another copy in Android's data mirror -- and they stayed in the global
     *    namespace after we unmounted ours, because of step 3. Binding the
     *    directory onto itself and taking that one mount out of the peer group
     *    gives everything stacked on top a private parent: measured, the other
     *    namespace sees 1 entry instead of 574.
     * 2. **`-o rbind`, not `--bind`.** A plain bind is not recursive, so the
     *    mount would show the bare root filesystem with /data, /system,
     *    /storage and everything else missing.
     * 3. **`rslave` on the result.** Android mounts `/` shared, so the rbind
     *    replica's root joins the same peer group as the real one -- and since
     *    an unmount propagates to the peers of the *parent*, unmounting the
     *    replicas reaches the host's `/system`, `/data` and the rest. (Measured
     *    the hard way: the phone lost every binary mid-session and had to be
     *    rebooted.) `rslave` makes the replica take changes from the host but
     *    send none back, which is what makes tearing it down safe.
     *
     * If step 3 ever fails the mount is deliberately left in place rather than
     * undone, because undoing it is the dangerous operation. Step 2 failing is
     * different: by then the mount point is private, so backing out is safe.
     */
    private val ANDROID_BIND = """
        if ! grep -q " ${'$'}R/$ANDROID_MOUNT " /proc/mounts; then
            if mount --bind "${'$'}R/$ANDROID_MOUNT" "${'$'}R/$ANDROID_MOUNT" 2>/dev/null; then
                mount -o rprivate none "${'$'}R/$ANDROID_MOUNT" 2>/dev/null
                if mount -o rbind / "${'$'}R/$ANDROID_MOUNT" 2>/dev/null; then
                    if ! mount -o rslave none "${'$'}R/$ANDROID_MOUNT" 2>/dev/null; then
                        echo '[!] Could not make /$ANDROID_MOUNT a slave mount.'
                        echo '[!] It is left mounted; unmounting it now would tear down'
                        echo '[!] the host filesystem with it. Reboot to clear it.'
                    fi
                else
                    umount "${'$'}R/$ANDROID_MOUNT" 2>/dev/null
                    echo '[!] could not mount the Android filesystem at /$ANDROID_MOUNT'
                fi
            else
                echo '[!] could not prepare /$ANDROID_MOUNT'
            fi
        fi
    """.trimIndent()

    /**
     * A shell in a desktop's chroot -- which does *not* need that desktop to be
     * running.
     *
     * Two modes, decided by the state file:
     *
     * - **Desktop running**: strictly a consumer. The desktop script owns
     *   `CHROOT_MOUNTS` and its `unmount_all()` tears down exactly that list,
     *   so nothing is mounted here; the session's DISPLAY / Wayland socket is
     *   passed through so GUI programs land on the running desktop.
     * - **Desktop not running**: a plain chroot shell. It sets up the same
     *   minimal four mounts the local session uses, all of which are in
     *   `CHROOT_MOUNTS`, so a desktop started later finds them (its `mount_all`
     *   is `is_mounted || mount`) and its stop still tears them down.
     *
     * `run`, `tmp`, `home` and `dev/shm` are deliberately left alone: the
     * desktop script mounts `run` as a *fresh* tmpfs per session and only
     * clears the stale one while it is unmounted, so pre-mounting it here would
     * quietly change how the desktop starts.
     *
     * [ANDROID_MOUNT] is not offered here either. It is outside `CHROOT_MOUNTS`,
     * so the script's `do_uninstall` would not unmount it before its `rm -rf` --
     * and that `rm -rf` would then walk into the host filesystem.
     */
    private fun desktopChroot(de: String): String {
        val infra = ScriptEnv.infraRoot(de)
        val name = de.uppercase()
        val liveEnv = if (de == "kde") {
            // The compositor picks its own socket number; startplasma-anland.sh
            // exports WAYLAND_DISPLAY inside the session and nothing writes it
            // down, so read it back off the runtime dir.
            """
            W=${'$'}(ls "${'$'}R/run/user/0" 2>/dev/null | grep -m1 '^wayland-[0-9]*${'$'}')
            [ -n "${'$'}W" ] && EXTRA="WAYLAND_DISPLAY=${'$'}W"
            """.trimIndent()
        } else {
            """
            EXTRA="DISPLAY=:0 PULSE_SERVER=unix:/tmp/pulseaudio.socket"
            """.trimIndent()
        }
        return """
            R='$infra/debian'
            STATE='$infra/state'
            EXTRA=""

            if [ ! -x "${'$'}R/bin/bash" ]; then
                echo 'The $name environment is not installed.'
                echo 'Install it from the dashboard first.'
                exit 1
            fi

            running=0
            if [ -f "${'$'}STATE" ]; then
                read -r spid srest < "${'$'}STATE" 2>/dev/null
                [ -n "${'$'}spid" ] && [ -d "/proc/${'$'}spid" ] && running=1
            fi

            if [ "${'$'}running" = 1 ]; then
                if ! grep -q " ${'$'}R/proc " /proc/mounts; then
                    echo "The $name chroot's mounts are not visible from this shell."
                    echo 'The desktop is running in a different mount namespace.'
                    exit 1
                fi
                $liveEnv
            else
                # Same four as the local session, all of them in the desktop
                # script's CHROOT_MOUNTS, so its stop still cleans them up.
                for m in proc sys dev dev/pts; do mkdir -p "${'$'}R/${'$'}m"; done
                $ENSURE_MOUNT
                ensure_mount proc "${'$'}R/proc" -t proc proc "${'$'}R/proc"
                ensure_mount sysfs "${'$'}R/sys" -t sysfs sys "${'$'}R/sys"
                ensure_mount /dev "${'$'}R/dev" --bind /dev "${'$'}R/dev"
                ensure_mount /dev/pts "${'$'}R/dev/pts" --bind /dev/pts "${'$'}R/dev/pts"
                echo '[*] $name is not running: this is a plain chroot shell.'
                echo '[*] No display, no session bus. Starting the desktop later'
                echo '[*] adds its own mounts on top of these.'
            fi

            exec chroot "${'$'}R" /usr/bin/env -i \
                HOME=/root \
                TERM=xterm-256color \
                PATH=$PATH \
                LANG=en_US.UTF-8 \
                TMPDIR=/tmp \
                XDG_RUNTIME_DIR=/run/user/0 \
                ${'$'}EXTRA \
                /bin/bash -i
        """.trimIndent()
    }
}
