package com.xiaoian.app.tools

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.system.exitProcess

/**
 * Minimal HTTPS downloader for the shell scripts, so they do not need
 * Termux's wget. Started as root the same way as the X server:
 *
 *   CLASSPATH=<apk> app_process /system/bin com.xiaoian.app.tools.Fetch <url> <file>
 *
 * With XIAOIAN_PROGRESS_ID set, progress lines go to stdout (see
 * [ProgressReporter]).
 *
 * Exit code 0 on success, 1 on any download error, 2 on bad usage.
 */
object Fetch {

    @JvmStatic
    fun main(args: Array<String>) {
        if (args.size != 2) {
            System.err.println("usage: Fetch <url> <output file>")
            exitProcess(2)
        }
        val rc = try {
            download(URL(args[0]), File(args[1]))
            0
        } catch (e: Exception) {
            System.err.println("[!] Fetch failed: ${args[0]}: $e")
            1
        }
        exitProcess(rc)
    }

    private fun download(url: URL, out: File) {
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 20_000
            conn.readTimeout = 60_000
            conn.instanceFollowRedirects = true
            // The GitHub API rejects requests without a User-Agent.
            conn.setRequestProperty("User-Agent", "Xiaoian")

            val code = conn.responseCode
            if (code !in 200..299) throw IOException("HTTP $code")

            val expected = conn.contentLengthLong
            val progress = ProgressReporter(System.out, expected)
            val written = conn.inputStream.use { input ->
                out.outputStream().use { output -> progress.copy(input, output) }
            }
            if (expected >= 0 && written != expected) {
                throw IOException("truncated: $written of $expected bytes")
            }
        } finally {
            conn.disconnect()
        }
    }
}
