package com.xiaoian.app.shell

sealed class ScriptMessage {
    data class Info(val text: String) : ScriptMessage()
    data class Warning(val text: String) : ScriptMessage()
    data class Error(val text: String) : ScriptMessage()
    data class Progress(val step: String) : ScriptMessage()
}

class ScriptOutputParser {
    fun parse(line: String): ScriptMessage {
        val trimmed = line.trim()
        return when {
            trimmed.startsWith("[*]") -> {
                val text = trimmed.removePrefix("[*]").trim()
                when {
                    text.startsWith("Downloading") -> ScriptMessage.Progress(text)
                    text.startsWith("Installing") -> ScriptMessage.Progress(text)
                    text.startsWith("Extracting") -> ScriptMessage.Progress(text)
                    text.startsWith("Booting") -> ScriptMessage.Progress(text)
                    else -> ScriptMessage.Info(text)
                }
            }
            trimmed.startsWith("[!] ERROR") -> ScriptMessage.Error(trimmed.removePrefix("[!] ERROR:").trim())
            trimmed.startsWith("[!] WARNING") -> ScriptMessage.Warning(trimmed.removePrefix("[!] WARNING:").trim())
            trimmed.startsWith("[!]") -> ScriptMessage.Warning(trimmed.removePrefix("[!]").trim())
            else -> ScriptMessage.Info(trimmed)
        }
    }
}
