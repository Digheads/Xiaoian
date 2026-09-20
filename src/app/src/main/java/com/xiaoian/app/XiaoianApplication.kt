package com.xiaoian.app

import com.termux.x11.LorieApp
import com.xiaoian.app.terminal.TerminalSessions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class XiaoianApplication : LorieApp() {
    override fun onCreate() {
        val oldHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val file = java.io.File(getExternalFilesDir(null), "crash.txt")
                val writer = java.io.PrintWriter(file)
                throwable.printStackTrace(writer)
                writer.flush()
                writer.close()
            } catch (e: Exception) {
                // Ignore
            }
            oldHandler?.uncaughtException(thread, throwable)
        }
        super.onCreate()

        // Terminal sessions live in this process. If Android killed it, their
        // shells lost the pty master and can never be reached again, but a
        // process that ignores the resulting SIGHUP would sit in the chroot
        // and block the next unmount. This is the only thing that clears them.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching { TerminalSessions.sweepOrphans(this@XiaoianApplication) }
        }
    }
}
