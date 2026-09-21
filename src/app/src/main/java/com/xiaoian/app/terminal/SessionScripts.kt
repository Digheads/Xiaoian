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
     * Internal storage inside a *desktop* chroot.
     *
     * Deliberately the same path the local rootfs reaches it by, under its
     * `/mnt/android` tree, so a file has one address in all three
     * environments. The desktop only gets this one branch of that tree, not
     * the whole recursive bind of `/`: see ARCHITECTURE.md.
     *
     * Mirrored in `xiaoian-*.sh`, whose `CHROOT_MOUNTS` is what tears it down.
     */
    const val DESKTOP_STORAGE = "mnt/android/storage/emulated/0"

    /**
     * `is_mountpoint DIR`: true when something is mounted on [DIR].
     *
     * Deliberately not `grep " $dir " /proc/mounts`, which is what this used to
     * be and what made every local terminal print four "could not mount" lines
     * after everything had in fact mounted. The app's rootfs is under
     * `context.filesDir`, which is `/data/user/0/<pkg>/files`, but inside an
     * app's mount namespace `/data/user/0` is reached through a bind mount, so
     * the kernel records the mount points under its own spelling of the path --
     * `/data/data/<pkg>/files/rootfs/proc`. The grep could never match, so the
     * "already mounted?" check always said no: the mounts were reported as
     * failures, and the next session stacked a second copy of all of them,
     * `/mnt/android`'s ~190 entries included (measured on the device: two full
     * copies of the Android tree).
     *
     * Comparing the device number against the parent's asks the kernel instead
     * of the path, so it works under either spelling. It cannot see a bind of a
     * directory onto another directory of the same filesystem; every mount here
     * brings its own filesystem, so that does not arise.
     */
    private val IS_MOUNTPOINT = """
        is_mountpoint() {
            [ -d "${'$'}1" ] || return 1
            [ "${'$'}(stat -c %d "${'$'}1" 2>/dev/null)" != "${'$'}(stat -c %d "${'$'}1/.." 2>/dev/null)" ]
        }
    """.trimIndent()

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
     *
     * Needs [IS_MOUNTPOINT] in scope.
     */
    private val ENSURE_MOUNT = """
        ensure_mount() {
            _label="${'$'}1"; _target="${'$'}2"; shift 2
            is_mountpoint "${'$'}_target" && return 0
            mount "${'$'}@" 2>/dev/null || { sleep 0.3; mount "${'$'}@" 2>/dev/null; }
            is_mountpoint "${'$'}_target" && return 0
            echo "[!] could not mount ${'$'}_label at ${'$'}_target"
            return 1
        }
    """.trimIndent()

    /** Both helpers, for the one place a script needs either of them. */
    private val MOUNT_HELPERS = "$IS_MOUNTPOINT\n\n$ENSURE_MOUNT"

    /**
     * The body shared by [unmountTree] and [wipeTree]: unmounts everything at
     * or under [target], deepest first, leaving a `mounted()` predicate in
     * scope so a caller can check the final state itself rather than trust
     * this function's own idea of whether it worked.
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
     * This *has* to work by path, because it has to enumerate submounts it
     * does not know the names of. So it matches both spellings of the app's
     * data directory: `umount` resolves either, but `/proc/mounts` only ever
     * lists the kernel's own, which inside the app's mount namespace is
     * `/data/data/<pkg>` even though the app hands us `/data/user/0/<pkg>`.
     * See [IS_MOUNTPOINT]. Matching only the app's spelling meant this found
     * nothing and quietly left the whole `/mnt/android` tree mounted -- which
     * is exactly the state that made [wipeTree] necessary in the first place:
     * a caller trusting a *separate* "did it unmount?" check before running
     * its own `rm -rf` is one bug away from recursing that delete straight
     * through a live bind of the real filesystem. Verified on the device, more
     * than once.
     */
    private fun unmountBody(target: String): String = """
        t='$target'
        case "${'$'}t" in
            /data/user/0/*) a="/data/data/${'$'}{t#/data/user/0/}" ;;
            /data/data/*)   a="/data/user/0/${'$'}{t#/data/data/}" ;;
            *)              a="${'$'}t" ;;
        esac
        mounted() { grep -q -e " ${'$'}t " -e " ${'$'}t/" -e " ${'$'}a " -e " ${'$'}a/" /proc/mounts; }
        i=0
        while [ ${'$'}i -lt 8 ] && mounted; do
            grep -e " ${'$'}t " -e " ${'$'}t/" -e " ${'$'}a " -e " ${'$'}a/" /proc/mounts |
            cut -d' ' -f2 | sort -r |
            while read -r m; do
                mount -o rslave none "${'$'}m" 2>/dev/null
                umount "${'$'}m" 2>/dev/null || umount -l "${'$'}m" 2>/dev/null
            done
            i=${'$'}((i + 1))
        done
    """.trimIndent()

    /**
     * Unmounts everything at or under [target] and succeeds only if nothing is
     * left. See [unmountBody] for how.
     *
     * Wrapped in a subshell because callers run it inside the long-lived root
     * shell, where a bare `exit` would take that shell down.
     */
    fun unmountTree(target: String): String = """
        (
        ${unmountBody(target)}
        ! mounted
        )
    """.trimIndent()

    /**
     * Unmounts everything at or under [target], then deletes it -- but only if,
     * immediately before the delete and in the same shell invocation, nothing
     * is mounted there any more.
     *
     * This exists as its own function, rather than "call [unmountTree], check
     * the Kotlin `Boolean`, then run a second `rm -rf` command" (which is what
     * this replaced), because that pattern has a gap: two separate root-shell
     * round trips, with nothing stopping a future change to either side from
     * quietly reintroducing the exact bug this fixes. A bug in the *check* used
     * to make `wipeRootfs` believe [ANDROID_MOUNT] -- a recursive bind of `/`
     * -- was already gone, so its `rm -rf` walked straight through the live
     * mount into the real filesystem. Here the check and the delete are the
     * same `if`, in the same script, so there is no such gap to reintroduce.
     *
     * Deliberately still checks even though [unmountBody] already retries: a
     * *caller* must never be the only thing standing between "still mounted"
     * and `rm -rf`, no matter how good the unmount loop above it is.
     */
    fun wipeTree(target: String): String = """
        (
        ${unmountBody(target)}
        if mounted; then
            echo "[!] refusing to delete ${'$'}t: still mounted"
            exit 1
        fi
        rm -rf "${'$'}t"
        )
    """.trimIndent()

    fun of(context: Context, spec: SessionSpec): String = when (spec) {
        is SessionSpec.Local -> local(BootstrapManager(context).prefixDir.absolutePath)
        is SessionSpec.DesktopChroot -> desktopChroot(spec.de)
        is SessionSpec.AndroidShell -> ANDROID_SHELL
    }

    /**
     * `zygote_env`: prints zygote's environment, one `NAME=value` per line.
     *
     * Android's own tools need more than a root shell inherits: `am`, `pm` and
     * `settings` start a VM through `app_process` and fail without
     * `BOOTCLASSPATH` and the `ANDROID_*` roots. Zygote has exactly the
     * environment every app and `adb shell` starts with, so it is copied
     * rather than guessed -- the values move between Android releases. Its
     * inherited socket fds (`ANDROID_SOCKET_*`) mean nothing to anyone else.
     *
     * Found with Android's own `pidof` (the caller sets `PIDOF`), which
     * matches the command line. Zygote's `comm` is "main", not "zygote64",
     * so scanning `/proc/<pid>/comm` never found it -- and forked once per
     * process while it looked.
     */
    /** `echo` lines, single-quoted: banners go through the shell as-is. */
    private fun banner(vararg lines: String): String =
        lines.joinToString("\n") { "echo '" + it.replace("'", "'\\''") + "'" } + "\necho"

    private val LOCAL_BANNER = banner(
        "XIAOIAN -- the app's own small Debian. No desktop needed.",
        "  /android           the whole Android filesystem (= /mnt/android)",
        "  /android/storage/emulated/0   internal storage (DCIM, Download, ...)",
        "  android CMD        run an Android command, e.g.:",
        "                       android dumpsys battery | grep level",
        "  android            an Android root shell here; exit comes back",
    )

    private val ANDROID_BANNER = banner(
        "ANDROID -- a root shell on the phone itself, like adb shell + su.",
        "  dumpsys, am, pm, settings, logcat work. Commands act on the phone.",
    )

    private fun desktopBanner(name: String) = banner(
        "$name -- the $name desktop's Debian tree.",
        "  Internal storage: /$DESKTOP_STORAGE",
        "  Android commands wont work: open a XIAOIAN or ANDROID terminal.",
    )

    private val ZYGOTE_ENV = """
        zygote_env() {
            set -- ${'$'}(${'$'}PIDOF zygote64 zygote 2>/dev/null)
            [ -n "${'$'}1" ] || return 1
            tr '\0' '\n' < "/proc/${'$'}1/environ" | grep -v -e '^ANDROID_SOCKET_' -e '^ANDROID_BOOTLOGO='
        }
    """.trimIndent()

    /**
     * The Android root shell. Starts in `/` like `adb shell`; Android's mksh
     * reads `/system/etc/mkshrc` for its usual prompt. Zygote's environment is
     * word-split into `env`: its values are paths and colon lists, no spaces.
     */
    private val ANDROID_SHELL = """
        PIDOF=/system/bin/pidof
        $ZYGOTE_ENV
        $ANDROID_BANNER
        cd /
        exec env -i ${'$'}(zygote_env) HOME=/ TERM=xterm-256color /system/bin/sh -i
    """.trimIndent()

    /**
     * `/usr/local/bin/android` in the tool rootfs: runs an Android command on
     * the host from inside the chroot, e.g. `android dumpsys input | grep foo`,
     * so Debian's tools and Android's can share a pipeline. Without arguments
     * it opens an Android shell in place.
     *
     * It chroots into [ANDROID_MOUNT], a recursive bind of the real `/` --
     * /system, /apex, /linkerconfig and /dev included -- so the binaries find
     * their linker, libraries and binder exactly as on the host.
     */
    private val ANDROID_WRAPPER_BODY = """
        #!/bin/sh
        # Runs an Android command on the host, outside this chroot.
        # Written by Xiaoian on every terminal start; edits are overwritten.
        A=/$ANDROID_MOUNT
        if [ ! -x "${'$'}A/system/bin/sh" ]; then
            echo "android: ${'$'}A is not mounted; open a new XIAOIAN terminal." >&2
            exit 1
        fi
        PIDOF="chroot ${'$'}A /system/bin/pidof"
        $ZYGOTE_ENV
        E=${'$'}(zygote_env) || { echo "android: zygote not found" >&2; exit 1; }
        if [ "${'$'}#" -eq 0 ]; then
            exec chroot "${'$'}A" /system/bin/env -i ${'$'}E HOME=/ TERM="${'$'}TERM" /system/bin/sh -i
        fi
        exec chroot "${'$'}A" /system/bin/env -i ${'$'}E HOME=/ TERM="${'$'}TERM" /system/bin/sh -c 'exec "${'$'}@"' android "${'$'}@"
    """.trimIndent()

    /**
     * Writes [ANDROID_WRAPPER_BODY] into the rootfs, fresh on every session
     * start so an app update updates it too. One single-quoted `printf`
     * argument per line rather than a heredoc: the session script around it
     * keeps its indentation, and an indented heredoc terminator never ends.
     */
    private val ANDROID_WRAPPER: String =
        "mkdir -p \"${'$'}R/usr/local/bin\"\n" +
            "printf '%s\\n' " +
            ANDROID_WRAPPER_BODY.lines().map { it.trim() }.joinToString(" ") { "'" + it.replace("'", "'\\''") + "'" } +
            " > \"${'$'}R/usr/local/bin/android\"\n" +
            "chmod 0755 \"${'$'}R/usr/local/bin/android\""

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
            echo 'The Debian bootstrap environment is not installed yet.'
            echo 'Start a desktop session once -- the app installs it on the way.'
            exit 1
        fi
        for m in proc sys dev dev/pts $ANDROID_MOUNT; do mkdir -p "${'$'}R/${'$'}m"; done

        # /android -> /mnt/android. Same place, one word to type. Guarded on
        # both -e and -L because a dangling symlink is invisible to -e, and
        # `ln -s` into an existing directory would nest rather than replace.
        if [ ! -e "${'$'}R/android" ] && [ ! -L "${'$'}R/android" ]; then
            ln -s "/$ANDROID_MOUNT" "${'$'}R/android"
        fi

        $MOUNT_HELPERS

        ensure_mount proc "${'$'}R/proc" -t proc proc "${'$'}R/proc"
        ensure_mount sysfs "${'$'}R/sys" -t sysfs sys "${'$'}R/sys"
        ensure_mount /dev "${'$'}R/dev" --bind /dev "${'$'}R/dev"
        ensure_mount /dev/pts "${'$'}R/dev/pts" --bind /dev/pts "${'$'}R/dev/pts"

        $ANDROID_BIND
        $ANDROID_WRAPPER
        $LOCAL_BANNER

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
        if ! is_mountpoint "${'$'}R/$ANDROID_MOUNT"; then
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
            W=${'$'}(${'$'}NS ls "${'$'}R/run/user/0" 2>/dev/null | grep -m1 '^wayland-[0-9]*${'$'}')
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

            NS=""
            if [ "${'$'}running" = 1 ]; then
                # A desktop started by an earlier app process -- before an
                # update, or a crash -- mounted its chroot in that process's
                # mount namespace, which this shell is not in. Join it through
                # the session wrapper, which lives there.
                if ! grep -q " ${'$'}R/proc " /proc/mounts; then
                    NS="nsenter -t ${'$'}spid -m --"
                    if ! ${'$'}NS grep -q " ${'$'}R/proc " /proc/mounts 2>/dev/null; then
                        echo "The $name chroot's mounts are not visible from this shell,"
                        echo "and its mount namespace could not be joined."
                        exit 1
                    fi
                fi
                $liveEnv
            else
                # Same as the local session, and every one of them is in the
                # desktop script's CHROOT_MOUNTS, so its stop still cleans
                # them up. Storage comes from the FUSE view for the reason
                # spelled out in xiaoian-*.sh: the raw /data/media/0 is
                # MediaProvider's backing store and writing there is invisible
                # to the media index.
                for m in proc sys dev dev/pts $DESKTOP_STORAGE; do mkdir -p "${'$'}R/${'$'}m"; done
                $MOUNT_HELPERS
                ensure_mount proc "${'$'}R/proc" -t proc proc "${'$'}R/proc"
                ensure_mount sysfs "${'$'}R/sys" -t sysfs sys "${'$'}R/sys"
                ensure_mount /dev "${'$'}R/dev" --bind /dev "${'$'}R/dev"
                ensure_mount /dev/pts "${'$'}R/dev/pts" --bind /dev/pts "${'$'}R/dev/pts"
                ensure_mount storage "${'$'}R/$DESKTOP_STORAGE" --bind /storage/emulated/0 "${'$'}R/$DESKTOP_STORAGE"
                echo '[*] $name is not running: this is a plain chroot shell.'
                echo '[*] No display, no session bus. Starting the desktop later'
                echo '[*] adds its own mounts on top of these.'
            fi

            ${desktopBanner(name)}
            exec ${'$'}NS chroot "${'$'}R" /usr/bin/env -i \
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
