package com.xiaoian.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val prefs = context.getSharedPreferences("xiaoian", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("auto_start_on_boot", false)) return

        val mode = prefs.getString("default_mode", "extend") ?: "extend"
        val de = prefs.getString("default_de", "kde") ?: "kde"

        val serviceIntent = Intent(context, XiaoianService::class.java).apply {
            action = XiaoianService.ACTION_START
            putExtra("mode", mode)
            putExtra("de", de)
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
    }
}
