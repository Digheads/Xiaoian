package com.xiaoian.app.shell

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

data class ShellResult(
    val success: Boolean,
    val error: String
)

class ShellExecutor {

    private companion object {
        /** How much of a failing command's output is kept for the UI. */
        const val ERROR_TAIL_LINES = 40
    }

    suspend fun run(command: String, onOutput: ((String) -> Unit)? = null): ShellResult = withContext(Dispatchers.IO) {
        var process: Process? = null
        try {
            Log.d("ShellExecutor", "Running: $command")
            process = ProcessBuilder("su", "-c", command)
                .redirectErrorStream(false)
                .start()
            val proc = process

            coroutineScope {
                // stderr is drained concurrently: read only after stdout ended,
                // a script writing more than a pipe buffer (64 KB) of stderr
                // would block forever.
                val stderr = async(Dispatchers.IO) {
                    val tail = ArrayDeque<String>()
                    proc.errorStream.bufferedReader().forEachLine { l ->
                        Log.e("ShellExecutor", "Error: $l")
                        tail.addLast(l)
                        if (tail.size > ERROR_TAIL_LINES) tail.removeFirst()
                    }
                    tail.joinToString("\n")
                }

                val lastOutputLines = ArrayDeque<String>()
                // The scripts report their own failures on stdout as "[!]"
                // lines. Those are worth far more than anything on stderr.
                val scriptErrors = ArrayDeque<String>()
                proc.inputStream.bufferedReader().forEachLine { l ->
                    Log.d("ShellExecutor", "Output: $l")
                    if (!ScriptOutputParser.isProtocolLine(l)) {
                        lastOutputLines.addLast(l)
                        // The scripts end a failure with a diagnostic dump; five
                        // lines used to cut off everything that mattered.
                        if (lastOutputLines.size > ERROR_TAIL_LINES) lastOutputLines.removeFirst()
                        if (l.trimStart().startsWith("[!]")) {
                            scriptErrors.addLast(l)
                            if (scriptErrors.size > ERROR_TAIL_LINES) scriptErrors.removeFirst()
                        }
                    }
                    onOutput?.invoke(l)
                }

                val exitCode = proc.waitFor()
                Log.d("ShellExecutor", "Exit code: $exitCode")

                // Order matters. A single stray line on stderr -- "Terminated"
                // from a `timeout` that fired earlier, say -- used to mask every
                // diagnostic the script had printed about the actual failure.
                var errStr = scriptErrors.joinToString("\n").trim()
                if (errStr.isEmpty()) errStr = stderr.await().trim()
                if (exitCode != 0 && errStr.isEmpty()) {
                    errStr = lastOutputLines.joinToString("\n").trim()
                }

                ShellResult(exitCode == 0, errStr)
            }
        } catch (e: Exception) {
            Log.e("ShellExecutor", "Failed to run command", e)
            ShellResult(false, e.message ?: "Unknown execution error")
        } finally {
            process?.destroy()
        }
    }
}
