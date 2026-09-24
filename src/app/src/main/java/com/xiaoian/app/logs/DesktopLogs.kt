package com.xiaoian.app.logs

import com.xiaoian.app.model.Desktop
import com.xiaoian.app.shell.RootShell

/** One log file in a desktop's infra root. */
data class LogFile(
    val path: String,
    /** Last modification, epoch milliseconds. */
    val modified: Long,
    val sizeBytes: Long,
) {
    val name: String get() = path.substringAfterLast('/')
}

/**
 * The logs the desktop scripts leave in their infra root: `de_debug.log` and
 * the previous run's `de_debug.log.1`, `unlock.log`, and XFCE's `xserver.log`.
 *
 * They are root-owned under `/data/local`, so both listing and reading go
 * through [RootShell.shared]. The directory is globbed rather than the names
 * written down here, so a log a script starts writing later shows up too.
 */
object DesktopLogs {

    /** Only the tail of a larger file is shown; a session log can grow without bound. */
    const val MAX_READ_BYTES = 512 * 1024L

    sealed interface Content {
        data class Text(val lines: List<String>, val truncated: Boolean) : Content
        data class Failed(val message: String) : Content
    }

    /** The desktop's logs, newest first. Null if the shell failed. */
    suspend fun list(de: Desktop): List<LogFile>? {
        val root = de.infraRoot
        val found = mutableListOf<LogFile>()
        val result = RootShell.shared.exec(
            "for f in '$root'/*.log '$root'/*.log.[0-9]*; do " +
                "[ -f \"\$f\" ] && echo \"\$(stat -c '%Y %s' \"\$f\") \$f\"; " +
                "done; true"
        ) { line ->
            val parts = line.split(' ', limit = 3)
            val mtime = parts.getOrNull(0)?.toLongOrNull()
            val size = parts.getOrNull(1)?.toLongOrNull()
            val path = parts.getOrNull(2)
            if (mtime != null && size != null && path != null) {
                found += LogFile(path, mtime * 1000, size)
            }
        }
        if (result.failure != null) return null
        return found.sortedByDescending { it.modified }
    }

    /** Reads [path], or its last [MAX_READ_BYTES] if it is larger. */
    suspend fun read(path: String): Content {
        // The path comes from list(), but it crosses into a root shell, so
        // anything that is not plainly one of our log files is refused.
        val allowed = Desktop.values().any { path.startsWith(it.infraRoot + "/") }
        if (!allowed || '\'' in path || "/../" in path) {
            return Content.Failed("Not a desktop log: $path")
        }
        val lines = mutableListOf<String>()
        var size: Long? = null
        // First line is the size from stat, the rest is the file.
        val result = RootShell.shared.exec(
            "stat -c %s '$path' && tail -c $MAX_READ_BYTES '$path'"
        ) { line ->
            if (size == null) {
                size = line.trim().toLongOrNull() ?: 0L
            } else {
                lines += line
            }
        }
        result.failure?.let { return Content.Failed(it) }
        if (result.exitCode != 0) {
            return Content.Failed(result.stderr.ifBlank { "Could not read $path" })
        }
        val truncated = (size ?: 0L) > MAX_READ_BYTES
        // tail -c starts mid-line; drop the fragment.
        if (truncated && lines.isNotEmpty()) lines.removeAt(0)
        return Content.Text(lines, truncated)
    }
}
