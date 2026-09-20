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

    /** `VAR=value` prefix to place in front of a script invocation. */
    fun prefix(context: Context): String =
        "ANLAND_BIN=${anlandBin(context)} " +
            "XIAOIAN_LIB_DIR=${libDir(context)} " +
            "XIAOIAN_ROOTFS_TARBALL=${rootfsTarball(context)}"
}
