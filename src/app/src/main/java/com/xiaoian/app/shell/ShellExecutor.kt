package com.xiaoian.app.shell

data class ShellResult(
    val success: Boolean,
    val error: String
)

/**
 * Runs a command as root and decides what to report when it fails.
 *
 * The root transition itself belongs to [RootShell]: this class no longer
 * spawns `su` per command, it hands the command to a shell that is already
 * open. Callers therefore pass a bare command -- a `su -c '...'` wrapper here
 * would open a second root shell inside the first one.
 */
class ShellExecutor(private val shell: RootShell = RootShell.shared) {

    private companion object {
        /** How much of a failing command's output is kept for the UI. */
        const val ERROR_TAIL_LINES = 40
    }

    suspend fun run(
        command: String,
        idleTimeoutMs: Long = RootShell.DEFAULT_IDLE_TIMEOUT_MS,
        onOutput: ((String) -> Unit)? = null,
    ): ShellResult {
        val lastOutputLines = ArrayDeque<String>()
        // The scripts report their own failures on stdout as "[!]" lines.
        // Those are worth far more than anything on stderr.
        val scriptErrors = ArrayDeque<String>()

        val result = shell.exec(command, idleTimeoutMs) { line ->
            if (!ScriptOutputParser.isProtocolLine(line)) {
                lastOutputLines.addLast(line)
                // The scripts end a failure with a diagnostic dump; five
                // lines used to cut off everything that mattered.
                if (lastOutputLines.size > ERROR_TAIL_LINES) lastOutputLines.removeFirst()
                if (line.trimStart().startsWith("[!]")) {
                    scriptErrors.addLast(line)
                    if (scriptErrors.size > ERROR_TAIL_LINES) scriptErrors.removeFirst()
                }
            }
            onOutput?.invoke(line)
        }

        // A broken shell is not a failing command, and saying so beats
        // reporting a command that never ran as having exited non-zero.
        result.failure?.let { return ShellResult(false, it) }

        // Order matters. A single stray line on stderr -- "Terminated" from a
        // `timeout` that fired earlier, say -- used to mask every diagnostic
        // the script had printed about the actual failure.
        var errStr = scriptErrors.joinToString("\n").trim()
        if (errStr.isEmpty()) errStr = result.stderr.trim()
        if (result.exitCode != 0 && errStr.isEmpty()) {
            errStr = lastOutputLines.joinToString("\n").trim()
        }

        return ShellResult(result.exitCode == 0, errStr)
    }
}
