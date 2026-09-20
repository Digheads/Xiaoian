package com.xiaoian.app.tools

import android.os.SystemClock
import java.io.InputStream
import java.io.OutputStream
import java.io.PrintStream

/**
 * Prints `@@PROGRESS <id> <done> <total>` lines (the scripts' progress
 * protocol) at most every [INTERVAL_MS], plus a final line. Inactive when
 * the script did not set XIAOIAN_PROGRESS_ID.
 */
class ProgressReporter(private val out: PrintStream, private val total: Long) {

    private val id: String? = System.getenv("XIAOIAN_PROGRESS_ID")?.takeIf { it.isNotBlank() }
    private var lastReport = 0L

    fun report(done: Long, force: Boolean = false) {
        if (id == null) return
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastReport < INTERVAL_MS) return
        lastReport = now
        out.println("@@PROGRESS $id $done $total")
        out.flush()
    }

    /** Copies [input] to [output] while reporting the bytes copied. */
    fun copy(input: InputStream, output: OutputStream): Long {
        val buffer = ByteArray(64 * 1024)
        var done = 0L
        report(0, force = true)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            output.write(buffer, 0, n)
            done += n
            report(done)
        }
        output.flush()
        report(done, force = true)
        return done
    }

    private companion object {
        const val INTERVAL_MS = 250L
    }
}
