package com.xiaoian.app.tools

import java.io.File
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.PrintStream
import kotlin.system.exitProcess

/**
 * Streams a file to stdout and reports progress on stderr, so the scripts
 * can show how far a `tar` extraction reading from a pipe has got:
 *
 *   { CLASSPATH=<apk> app_process /system/bin com.xiaoian.app.tools.Cat <file> 2>&3 | tar -xJf - ...; } 3>&1
 *
 * Exit code 0 on success, 1 on a read/write error, 2 on bad usage.
 */
object Cat {

    @JvmStatic
    fun main(args: Array<String>) {
        if (args.size != 1) {
            System.err.println("usage: Cat <file>")
            exitProcess(2)
        }
        val file = File(args[0])
        val rc = try {
            // Raw fd 1: System.out is a PrintStream that swallows write errors
            // (e.g. tar exiting early), which must fail this process instead.
            FileOutputStream(FileDescriptor.out).use { stdout ->
                file.inputStream().use { input ->
                    ProgressReporter(PrintStream(System.err, true), file.length()).copy(input, stdout)
                }
            }
            0
        } catch (e: Exception) {
            System.err.println("[!] Cat failed: ${args[0]}: $e")
            1
        }
        exitProcess(rc)
    }
}
