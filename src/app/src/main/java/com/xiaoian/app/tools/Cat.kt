package com.xiaoian.app.tools

import org.tukaani.xz.XZInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.io.PrintStream
import kotlin.system.exitProcess

/**
 * Streams a file to stdout, optionally decompressing xz on the way, and
 * reports progress on stderr so the scripts can show how far a `tar`
 * extraction reading from a pipe has got:
 *
 *   { CLASSPATH=<apk> app_process /system/bin com.xiaoian.app.tools.Cat --xz <file> 2>&3 \
 *       | tar -xf - -C <dest>; } 3>&1
 *
 * The `--xz` mode exists so nothing outside the APK is needed to unpack a
 * rootfs. Android's tar is toybox's, which has no xz support at all, and the
 * only xz-capable tar on a rooted phone is the busybox the root solution
 * happens to ship -- Magisk, KernelSU and APatch each keep it somewhere
 * different, and the next one would be somewhere else again. The app already
 * carries an XZ decoder for its own bootstrap, so it does the decompressing
 * and hands plain tar to the tar that is always there.
 *
 * Progress counts *compressed* bytes read, not bytes written: the file's size
 * is the only total known up front, so this is what gives a real percentage.
 *
 * Exit code 0 on success, 1 on a read/write error, 2 on bad usage.
 */
object Cat {

    @JvmStatic
    fun main(args: Array<String>) {
        var xz = false
        var path: String? = null
        for (arg in args) {
            when {
                arg == "--xz" -> xz = true
                path == null -> path = arg
                else -> usage()
            }
        }
        if (path == null) usage()

        val file = File(path)
        val progress = ProgressReporter(PrintStream(System.err, true), file.length())
        val rc = try {
            // Raw fd 1: System.out is a PrintStream that swallows write errors
            // (e.g. tar exiting early), which must fail this process instead.
            FileOutputStream(FileDescriptor.out).use { stdout ->
                CountingInputStream(BufferedInputStream(FileInputStream(file))).use { counted ->
                    val source: InputStream = if (xz) XZInputStream(counted) else counted
                    val buffer = ByteArray(64 * 1024)
                    progress.report(0, force = true)
                    while (true) {
                        val n = source.read(buffer)
                        if (n < 0) break
                        stdout.write(buffer, 0, n)
                        progress.report(counted.count)
                    }
                    stdout.flush()
                    progress.report(counted.count, force = true)
                }
            }
            0
        } catch (e: Exception) {
            System.err.println("[!] Cat failed: $path: $e")
            1
        }
        exitProcess(rc)
    }

    private fun usage(): Nothing {
        System.err.println("usage: Cat [--xz] <file>")
        exitProcess(2)
    }

    /** Counts the bytes actually read from the file, under any decompressor. */
    private class CountingInputStream(source: InputStream) : FilterInputStream(source) {
        var count = 0L
            private set

        override fun read(): Int = super.read().also { if (it >= 0) count++ }

        override fun read(b: ByteArray, off: Int, len: Int): Int =
            super.read(b, off, len).also { if (it > 0) count += it }

        override fun skip(n: Long): Long = super.skip(n).also { count += it }
    }
}
