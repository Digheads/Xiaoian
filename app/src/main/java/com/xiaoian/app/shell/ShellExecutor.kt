package com.xiaoian.app.shell

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader

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
                
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val errorReader = BufferedReader(InputStreamReader(process.errorStream))
            
            var line: String?
            var lastOutputLines = mutableListOf<String>()
            while (reader.readLine().also { line = it } != null) {
                val l = line!!
                Log.d("ShellExecutor", "Output: $l")
                lastOutputLines.add(l)
                if (lastOutputLines.size > 5) lastOutputLines.removeAt(0)
                onOutput?.invoke(l)
            }
            
            val stderrBuilder = java.lang.StringBuilder()
            while (errorReader.readLine().also { line = it } != null) {
                val l = line!!
                Log.e("ShellExecutor", "Error: $l")
                stderrBuilder.append(l).append("\n")
            }
            
            val exitCode = process.waitFor()
            Log.d("ShellExecutor", "Exit code: $exitCode")
            
            var errStr = stderrBuilder.toString().trim()
            if (exitCode != 0 && errStr.isEmpty()) {
                errStr = lastOutputLines.joinToString("\n").trim()
            }
            
            ShellResult(exitCode == 0, errStr)
        } catch (e: Exception) {
            Log.e("ShellExecutor", "Failed to run command", e)
            ShellResult(false, e.message ?: "Unknown execution error")
        } finally {
            process?.destroy()
        }
    }
}
