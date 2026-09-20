package com.xiaoian.app.shell

sealed class ScriptMessage {
    data class Info(val text: String) : ScriptMessage()
    data class Warning(val text: String) : ScriptMessage()
    data class Error(val text: String) : ScriptMessage()

    /** A step this run will go through, announced before the first one starts. */
    data class StepPlanned(val id: String, val title: String) : ScriptMessage()
    data class StepStarted(val id: String) : ScriptMessage()
    data class StepDone(val id: String) : ScriptMessage()

    /** Byte progress of a step; [total] is negative when unknown. */
    data class BytesProgress(val id: String, val done: Long, val total: Long) : ScriptMessage()

    /** apt/dpkg status of the running step (APT::Status-Fd lines). */
    data class AptProgress(val percent: Float, val text: String) : ScriptMessage()
}

/**
 * Parses script stdout: the human "[*]" / "[!]" lines and the progress
 * protocol ("@@PLAN", "@@STEP", "@@PROGRESS", "@@DONE", apt "pmstatus:" /
 * "dlstatus:"), see the progress section of the scripts.
 */
class ScriptOutputParser {
    fun parse(line: String): ScriptMessage {
        val trimmed = line.trim()
        return parseProtocol(trimmed) ?: when {
            trimmed.startsWith("[*]") -> ScriptMessage.Info(trimmed.removePrefix("[*]").trim())
            trimmed.startsWith("[!] ERROR") -> ScriptMessage.Error(trimmed.removePrefix("[!] ERROR:").trim())
            trimmed.startsWith("[!] WARNING") -> ScriptMessage.Warning(trimmed.removePrefix("[!] WARNING:").trim())
            trimmed.startsWith("[!]") -> ScriptMessage.Warning(trimmed.removePrefix("[!]").trim())
            else -> ScriptMessage.Info(trimmed)
        }
    }

    private fun parseProtocol(line: String): ScriptMessage? {
        if (line.startsWith("@@")) {
            val parts = line.split(' ', limit = 3)
            val id = parts.getOrNull(1) ?: return null
            return when (parts[0]) {
                "@@PLAN" -> ScriptMessage.StepPlanned(id, parts.getOrNull(2) ?: id)
                "@@STEP" -> ScriptMessage.StepStarted(id)
                "@@DONE" -> ScriptMessage.StepDone(id)
                "@@PROGRESS" -> {
                    val numbers = parts.getOrNull(2)?.split(' ') ?: return null
                    val done = numbers.getOrNull(0)?.toLongOrNull() ?: return null
                    val total = numbers.getOrNull(1)?.toLongOrNull() ?: -1L
                    ScriptMessage.BytesProgress(id, done, total)
                }
                else -> null
            }
        }
        // "pmstatus:<package>:<percent>:<message>" / "dlstatus:<n>:<percent>:<message>"
        if (line.startsWith("pmstatus:") || line.startsWith("dlstatus:")) {
            val parts = line.split(':', limit = 4)
            val percent = parts.getOrNull(2)?.toFloatOrNull() ?: return null
            return ScriptMessage.AptProgress(percent, parts.getOrNull(3)?.trim().orEmpty())
        }
        return null
    }

    companion object {
        /** Lines of the progress protocol: never useful in an error message. */
        fun isProtocolLine(line: String): Boolean {
            val trimmed = line.trimStart()
            return trimmed.startsWith("@@") || trimmed.startsWith("pmstatus:") ||
                trimmed.startsWith("dlstatus:")
        }
    }
}
