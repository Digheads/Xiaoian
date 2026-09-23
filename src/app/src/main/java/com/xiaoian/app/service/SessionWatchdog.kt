package com.xiaoian.app.service

import com.xiaoian.app.model.Desktop
import com.xiaoian.app.shell.ShellExecutor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Polls a running desktop's state file so the app notices when it dies on its
 * own -- a logout from inside, or a crash -- and reports the virtual lock
 * state, which the script's own watcher lifts without the app hearing about it.
 *
 * Extracted from [XiaoianService]: the polling lives here, the decisions about
 * what a lock change or a death means stay with the service via the callbacks.
 */
class SessionWatchdog(
    private val scope: CoroutineScope,
    private val shell: ShellExecutor,
) {
    private var job: Job? = null

    val isActive: Boolean get() = job?.isActive == true

    /**
     * Watches [de]. [onLockChanged] is invoked on the main thread whenever the
     * lock state changes; [onDied] once, on the main thread, when the session's
     * process is gone.
     */
    fun start(de: Desktop, onLockChanged: (Boolean) -> Unit, onDied: suspend () -> Unit) {
        job?.cancel()
        job = scope.launch(Dispatchers.IO) {
            val stateFile = "${ScriptEnv.infraRoot(de)}/state"
            // One cheap check every five seconds on the shared root shell. This
            // used to be a `while true` loop inside a root shell of its own --
            // a whole extra Magisk prompt just to watch a file.
            val lockFile = "${ScriptEnv.infraRoot(de)}/locked"
            // Also reports the lock, which the script's watcher lifts on its own.
            // A subshell: this runs in the shared root shell, where a bare
            // `exit` would end the shell itself.
            val alive = "(p=\$(awk '{print \$1}' $stateFile 2>/dev/null); " +
                "[ -n \"\$p\" ] && [ -d \"/proc/\$p\" ] || exit 1; " +
                "[ -f $lockFile ] && echo locked || echo unlocked)"
            try {
                while (isActive) {
                    delay(5_000)
                    var lockState: String? = null
                    if (!shell.run(alive) { lockState = it.trim() }.success) break
                    withContext(Dispatchers.Main) { onLockChanged(lockState == "locked") }
                }
                withContext(Dispatchers.Main) { onDied() }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}
