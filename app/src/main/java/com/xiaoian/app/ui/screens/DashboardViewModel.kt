package com.xiaoian.app.ui.screens

import android.app.Application
import android.content.Intent
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.xiaoian.app.service.SessionState
import com.xiaoian.app.service.SetupProgress
import com.xiaoian.app.service.StorageInfo
import com.xiaoian.app.service.XiaoianService
import com.xiaoian.app.shell.ShellExecutor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class UninstallState(
    val inProgress: Boolean = false,
    val de: String = "",       // "xfce" or "kde"
    val output: String = ""
)

class DashboardViewModel(application: Application) : AndroidViewModel(application) {

    val sessionState: StateFlow<SessionState> = com.xiaoian.app.service.SessionManagerProvider.sessionManager.state
    val setupProgress: StateFlow<SetupProgress> = com.xiaoian.app.service.SessionManagerProvider.sessionManager.setup

    private val shellExecutor = ShellExecutor()

    private val _xfceStorage = MutableStateFlow<StorageInfo?>(null)
    val xfceStorage: StateFlow<StorageInfo?> = _xfceStorage.asStateFlow()

    private val _kdeStorage = MutableStateFlow<StorageInfo?>(null)
    val kdeStorage: StateFlow<StorageInfo?> = _kdeStorage.asStateFlow()

    private val _storageLoading = MutableStateFlow(false)
    val storageLoading: StateFlow<Boolean> = _storageLoading.asStateFlow()

    private val _uninstallState = MutableStateFlow(UninstallState())
    val uninstallState: StateFlow<UninstallState> = _uninstallState.asStateFlow()

    companion object {
        private const val TAG = "DashboardViewModel"
        private const val XFCE_INFRA = "/data/local/xiaoian-x11-xfce"
        private const val KDE_INFRA = "/data/local/xiaoian-wayland-kde"
    }

    init {
        refreshStorage()
    }

    fun startSession(mode: String, de: String) {
        val intent = Intent(getApplication(), XiaoianService::class.java).apply {
            action = XiaoianService.ACTION_START
            putExtra("mode", mode)
            putExtra("de", de)
        }
        getApplication<Application>().startForegroundService(intent)
    }

    fun stopSession() {
        val intent = Intent(getApplication(), XiaoianService::class.java).apply {
            action = XiaoianService.ACTION_STOP
        }
        getApplication<Application>().startService(intent)
    }

    fun refreshStorage() {
        viewModelScope.launch {
            _storageLoading.value = true
            _xfceStorage.value = queryStorage(XFCE_INFRA)
            _kdeStorage.value = queryStorage(KDE_INFRA)
            _storageLoading.value = false
        }
    }

    fun uninstall(de: String) {
        viewModelScope.launch {
            val scriptName = if (de == "kde") "xiaoian-wayland-kde.sh" else "xiaoian-x11-xfce.sh"
            val infraRoot = if (de == "kde") "/data/local/xiaoian-wayland-kde" else "/data/local/xiaoian-x11-xfce"
            val scriptPath = "$infraRoot/$scriptName"

            _uninstallState.value = UninstallState(inProgress = true, de = de, output = "Removing...")

            // Extract the script first (same logic as XiaoianService)
            try {
                val app = getApplication<Application>()
                val scriptFile = java.io.File(app.externalCacheDir, scriptName)
                app.assets.open(scriptName).use { input ->
                    scriptFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                shellExecutor.run("mkdir -p $infraRoot")
                shellExecutor.run("cp ${scriptFile.absolutePath} $scriptPath")
                shellExecutor.run("chmod +x $scriptPath")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to extract script for uninstall", e)
                _uninstallState.value = UninstallState(inProgress = false, de = de, output = "Error: ${e.message}")
                return@launch
            }

            val result = shellExecutor.run("$scriptPath -u") { line ->
                _uninstallState.value = _uninstallState.value.copy(output = line)
            }

            if (result.success) {
                _uninstallState.value = UninstallState(inProgress = false, de = de, output = "Successfully removed!")
            } else {
                _uninstallState.value = UninstallState(inProgress = false, de = de, output = "Error: ${result.error}")
            }

            refreshStorage()
        }
    }

    private suspend fun queryStorage(infraRoot: String): StorageInfo {
        // Check if installed
        val existsResult = shellExecutor.run("test -d $infraRoot/debian")
        if (!existsResult.success) {
            return StorageInfo(installed = false)
        }

        val rootfsSize = getDirSize("$infraRoot/debian")
        val installerSize = getDirSize("$infraRoot/install_files")

        return StorageInfo(
            rootfsSize = rootfsSize,
            installerSize = installerSize,
            installed = true
        )
    }

    private suspend fun getDirSize(path: String): Long {
        val result = shellExecutor.run("du -sb $path 2>/dev/null | head -1")
        // du -sb outputs: <bytes>\t<path>
        // We capture it from the last output line
        var size = 0L
        shellExecutor.run("du -sb $path 2>/dev/null | head -1") { line ->
            val parts = line.trim().split("\t")
            if (parts.isNotEmpty()) {
                size = parts[0].toLongOrNull() ?: 0L
            }
        }
        return size
    }
}
