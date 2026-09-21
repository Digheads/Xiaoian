package com.xiaoian.app.ui.screens

import android.app.Application
import android.content.Intent
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.xiaoian.app.service.SessionState
import com.xiaoian.app.service.SetupProgress
import com.xiaoian.app.service.StorageInfo
import com.xiaoian.app.display.DisplayDetector
import com.xiaoian.app.display.ExternalDisplay
import com.xiaoian.app.service.XiaoianService
import com.xiaoian.app.settings.AppPrefs
import com.xiaoian.app.shell.RootShell
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

    /**
     * What the last started session used, for the dashboard's radio buttons to
     * open on. See [AppPrefs.lastDe].
     */
    val lastDe: String = AppPrefs.lastDe(application)
    val lastMode: String = AppPrefs.lastMode(application)

    /**
     * Displays the desktop could be sent to, kept current while the dashboard
     * is open: the picker is most useful exactly when someone is plugging
     * something in, so a list read once at startup would be the stale one.
     */
    private val displayDetector = DisplayDetector(application)
    private val _externalDisplays = MutableStateFlow(displayDetector.externalDisplays())
    val externalDisplays: StateFlow<List<ExternalDisplay>> = _externalDisplays.asStateFlow()

    private val displayWatch = displayDetector.observe {
        _externalDisplays.value = displayDetector.externalDisplays()
    }

    override fun onCleared() {
        displayWatch.close()
        super.onCleared()
    }

    companion object {
        private const val TAG = "DashboardViewModel"
        private const val XFCE_INFRA = "/data/local/xiaoian-x11-xfce"
        private const val KDE_INFRA = "/data/local/xiaoian-wayland-kde"
    }

    init {
        refreshStorage()
    }

    /**
     * @param display which external display to use, or null to let the script
     *   pick the first one it finds. Ignored in local mode.
     */
    fun startSession(mode: String, de: String, display: ExternalDisplay? = null) {
        // The only place a desktop starts from the dashboard, so the only place
        // that has to remember what it was started with.
        AppPrefs.setLastSelection(getApplication(), de, mode)
        val intent = Intent(getApplication(), XiaoianService::class.java).apply {
            action = XiaoianService.ACTION_START
            putExtra("mode", mode)
            putExtra("de", de)
            if (mode != "local" && display != null) {
                putExtra("displayId", display.id)
                putExtra("displaySize", display.size)
            }
        }
        getApplication<Application>().startForegroundService(intent)
    }

    fun stopSession() {
        val intent = Intent(getApplication(), XiaoianService::class.java).apply {
            action = XiaoianService.ACTION_STOP
        }
        getApplication<Application>().startService(intent)
    }

    /**
     * Blanks and locks the phone's own screen while the desktop keeps running
     * on the external display (the scripts' `-k`). Meaningless in local mode,
     * where the desktop *is* the phone screen.
     */
    fun lockPhone() {
        val intent = Intent(getApplication(), XiaoianService::class.java).apply {
            action = XiaoianService.ACTION_LOCK
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
            val scriptName = com.xiaoian.app.service.ScriptEnv.scriptName(de)
            val infraRoot = com.xiaoian.app.service.ScriptEnv.infraRoot(de)
            val scriptPath = com.xiaoian.app.service.ScriptEnv.scriptPath(de)

            _uninstallState.value = UninstallState(inProgress = true, de = de, output = "Removing...")

            // Same reason as stopping a session, with a sharper edge: the
            // script's do_uninstall ends in `rm -rf`, and an open chroot shell
            // keeps a lazily-unmounted /dev alive for it to walk into and
            // delete the host's device nodes.
            com.xiaoian.app.terminal.TerminalSessions.closeAllFor(getApplication(), de)

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

            val env = com.xiaoian.app.service.ScriptEnv.prefix(getApplication())
            // Uninstall unmounts and then deletes a multi-gigabyte chroot: it
            // runs long and goes quiet, so it gets a root shell of its own
            // rather than holding the shared one the dashboard is polling.
            val uninstallShell = RootShell.dedicated("uninstall-$de")
            val result = try {
                ShellExecutor(uninstallShell).run(
                    "$env $scriptPath -u",
                    idleTimeoutMs = RootShell.NO_TIMEOUT,
                ) { line ->
                    _uninstallState.value = _uninstallState.value.copy(output = line)
                }
            } finally {
                uninstallShell.close()
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
        val existsResult = shellExecutor.run("test -d $infraRoot/debian")
        if (!existsResult.success) {
            return StorageInfo(installed = false)
        }
        // The whole infra root: the chroot plus the script, logs and state.
        // Downloaded assets are shared between both desktops and live in the
        // app's files/downloads, so they are deliberately not counted here.
        return StorageInfo(sizeBytes = getDirSize(infraRoot), installed = true)
    }

    private suspend fun getDirSize(path: String): Long {
        // du -sb prints "<bytes>\t<path>"
        var size = 0L
        shellExecutor.run("du -sb $path 2>/dev/null | head -1") { line ->
            val parts = line.trim().split("\t")
            if (parts.isNotEmpty()) {
                size = parts[0].toLongOrNull() ?: size
            }
        }
        return size
    }
}
