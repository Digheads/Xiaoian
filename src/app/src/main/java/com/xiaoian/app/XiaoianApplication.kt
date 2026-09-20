package com.xiaoian.app

import com.termux.x11.LorieApp

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
    }
}
