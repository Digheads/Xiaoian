package com.xiaoian.app.service

import android.content.Context
import java.io.File

/**
 * Environment the desktop scripts expect from the app.
 *
 * The scripts are only ever started by the app, never by hand, so they do not
 * discover any of this themselves — whatever they need is passed in as an
 * environment prefix on the command line.
 */
object ScriptEnv {

    /**
     * The Anland display daemon. It ships inside the APK named `lib*.so` so the
     * packager extracts it into the native library directory with the execute
     * bit set; `/data/data` itself is non-executable (W^X).
     *
     * Requires `useLegacyPackaging = true` in the application module, otherwise
     * this path points inside the APK and nothing can be exec'd from it.
     */
    fun anlandBin(context: Context): String =
        File(context.applicationInfo.nativeLibraryDir, "libanland.so").absolutePath

    /**
     * Directory the packager extracted the native libraries into.
     *
     * `CmdEntryPoint` needs this to dlopen `libXlorie.so`: it runs under
     * `app_process`, where the only context available is the system one, so it
     * cannot ask the framework where this app's libraries live.
     */
    fun libDir(context: Context): String = context.applicationInfo.nativeLibraryDir

    /**
     * The Debian tarball both desktops extract their chroot from.
     *
     * The app downloads it, so the app decides where it lives; the scripts used
     * to hard-code the same path separately, which is how the two drift apart.
     */
    fun rootfsTarball(context: Context): String =
        BootstrapManager(context).rootfsTarball.absolutePath

    /**
     * The in-app terminal's open session ids, one per line.
     *
     * A chroot terminal can be opened before its desktop, and it mounts the
     * chroot itself when it is. The start script clears out whatever it finds
     * in the chroot before it begins, which killed exactly that shell; with
     * this it can tell a live in-app terminal from a crashed session's
     * leftovers. The file need not exist -- the scripts treat a missing one as
     * "no terminals open".
     */
    fun openSessionsFile(context: Context): String =
        com.xiaoian.app.terminal.TerminalSessions.openListFile(context).absolutePath

    /**
     * `VAR=value` prefix to place in front of a script invocation.
     *
     * [displayId] and [displaySize] name the external display the user picked,
     * and are only meaningful for a start in extend or mirror mode. Left out,
     * the scripts fall back to their own `dumpsys display` parsing, which takes
     * the first external display it finds -- fine with one screen, arbitrary
     * with two.
     */
    fun prefix(
        context: Context,
        displayId: Int? = null,
        displaySize: String? = null,
    ): String = buildString {
        append("ANLAND_BIN=${anlandBin(context)} ")
        append("XIAOIAN_LIB_DIR=${libDir(context)} ")
        append("XIAOIAN_ROOTFS_TARBALL=${rootfsTarball(context)} ")
        append("XIAOIAN_OPEN_SESSIONS=${openSessionsFile(context)}")
        if (displayId != null) append(" XIAOIAN_DISPLAY_ID=$displayId")
        if (!displaySize.isNullOrBlank()) append(" XIAOIAN_DISPLAY_SIZE=$displaySize")
    }

    /**
     * Where a desktop's script and chroot live. The same `if (de == "kde")`
     * ternary used to sit at five call sites, which is how two of them end up
     * disagreeing.
     */
    fun infraRoot(de: String): String =
        if (de == "kde") "/data/local/xiaoian-wayland-kde" else "/data/local/xiaoian-x11-xfce"

    /** File name of the desktop's start script, as bundled in the APK assets. */
    fun scriptName(de: String): String =
        if (de == "kde") "xiaoian-wayland-kde.sh" else "xiaoian-x11-xfce.sh"

    /** Absolute path the script is installed to and run from. */
    fun scriptPath(de: String): String = "${infraRoot(de)}/${scriptName(de)}"
}
