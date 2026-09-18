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
                        if (tail.size > 20) tail.removeFirst()
                    }
                    tail.joinToString("\n")
                }

                val lastOutputLines = ArrayDeque<String>()
                proc.inputStream.bufferedReader().forEachLine { l ->
                    Log.d("ShellExecutor", "Output: $l")
                    if (!ScriptOutputParser.isProtocolLine(l)) {
                        lastOutputLines.addLast(l)
                        if (lastOutputLines.size > 5) lastOutputLines.removeFirst()
                    }
                    onOutput?.invoke(l)
                }

                val exitCode = proc.waitFor()
                Log.d("ShellExecutor", "Exit code: $exitCode")

                var errStr = stderr.await().trim()
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
