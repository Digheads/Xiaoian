package com.xiaoian.app.service

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.delay

/**
 * Closes the vendored desktop frontends.
 *
 * Extracted from [XiaoianService]. Termux:X11 is closed through its own
 * `ACTION_STOP` broadcast (which `LorieApp` turns into `finishAndRemoveTask`);
 * Anland has no such receiver, so its activity is finished directly through
 * the static reference it keeps.
 */
class FrontendController(private val context: Context) {

    /** Closes whichever desktop frontend is on screen. */
    fun close() {
        runCatching {
            context.sendBroadcast(Intent("com.termux.x11.ACTION_STOP").apply {
                setPackage(context.packageName)
            })
        }.onFailure { Log.w(TAG, "Could not stop the X11 frontend", it) }

        runCatching {
            com.anland.termux.MainActivity.sInstance?.let { activity ->
                activity.runOnUiThread { activity.finishAndRemoveTask() }
            }
        }.onFailure { Log.w(TAG, "Could not close the Anland frontend", it) }
    }

    /**
     * Waits until neither frontend has a task left. The task, not the
     * activity, is what matters: `am start` reuses a singleInstance task for
     * as long as it exists, wherever it is.
     */
    suspend fun waitForClosed() {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val frontends = setOf("com.termux.x11.MainActivity", "com.anland.termux.MainActivity")
        fun open() = runCatching {
            am.appTasks.any { it.taskInfo.baseActivity?.className in frontends }
        }.getOrDefault(false)
        val deadline = SystemClock.elapsedRealtime() + 3_000
        while (open() && SystemClock.elapsedRealtime() < deadline) {
            delay(100)
        }
    }

    private companion object {
        const val TAG = "FrontendController"
    }
}
