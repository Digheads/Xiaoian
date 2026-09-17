package com.xiaoian.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.xiaoian.app.MainActivity
import com.xiaoian.app.R
import com.xiaoian.app.shell.ScriptMessage
import com.xiaoian.app.shell.ScriptOutputParser
import com.xiaoian.app.shell.ShellExecutor
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

class XiaoianService : LifecycleService() {

    companion object {
        const val NOTIFICATION_ID = 1
        const val CHANNEL_ID = "xiaoian_session"

        const val ACTION_START = "com.xiaoian.app.START"
        const val ACTION_STOP = "com.xiaoian.app.STOP"
        const val ACTION_LOCK = "com.xiaoian.app.LOCK"
        const val ACTION_UNLOCK = "com.xiaoian.app.UNLOCK"
        const val ACTION_TERMINAL = "com.xiaoian.app.TERMINAL"
    }

    val sessionManager = SessionManagerProvider.sessionManager
    private val shellExecutor = ShellExecutor()
    private val scriptParser = ScriptOutputParser()

    private var currentMode: String = "extend"
    private var currentDE: String = "kde"
    private var watchdogJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START -> startSession(
                mode = intent.getStringExtra("mode") ?: "extend",
                de = intent.getStringExtra("de") ?: "kde"
            )
            ACTION_STOP -> stopSession()
            ACTION_LOCK -> lockPhone()
            ACTION_UNLOCK -> unlockPhone()
            ACTION_TERMINAL -> openTerminal()
        }
        return START_STICKY
    }

    private fun startSession(mode: String, de: String) {
        currentMode = mode
        currentDE = de
        sessionManager.updateState(SessionState.Starting)
        startForeground(NOTIFICATION_ID, buildNotification("Starting session..."))

        lifecycleScope.launch {
            try {
                val scriptName = if (de == "kde") "xiaoian-wayland-kde.sh" else "xiaoian-x11-xfce.sh"
                
                // Extract script from assets to /data/local/tmp to avoid SELinux/noexec issues
                val scriptPath = "/data/local/tmp/$scriptName"
                val scriptFile = File(externalCacheDir, scriptName) // temporary staging in externalCacheDir so root can read it
                
                if (!scriptFile.exists() || scriptFile.length() == 0L) {
                    assets.open(scriptName).use { input ->
                        scriptFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    // Move to /data/local/tmp and make executable using root
                    shellExecutor.run("cp ${scriptFile.absolutePath} $scriptPath")
                    shellExecutor.run("chmod +x $scriptPath")
                }
                
                val result = shellExecutor.run(
                    command = "$scriptPath -s --$mode"
                ) { line ->
                    val msg = scriptParser.parse(line)
                    if (msg is ScriptMessage.Progress) {
                        sessionManager.updateState(SessionState.Installing(msg.step))
                        updateNotification(msg.step)
                    }
                }

                if (result.success) {
                    sessionManager.updateState(SessionState.Running(mode, de, false))
                    updateNotification("Session running")
                    startSessionWatchdog()
                } else {
                    val errMsg = result.error.ifEmpty { "Failed to start session (exit code non-zero)" }
                    sessionManager.updateState(SessionState.Error(errMsg))
                    stopForeground(STOP_FOREGROUND_REMOVE)
                }
            } catch (e: Exception) {
                sessionManager.updateState(SessionState.Error(e.message ?: "Unknown error"))
                stopForeground(STOP_FOREGROUND_REMOVE)
            }
        }
    }

    private fun stopSession() {
        sessionManager.updateState(SessionState.Stopping)
        updateNotification("Stopping session...")
        lifecycleScope.launch {
            val scriptName = if (currentDE == "kde") "xiaoian-wayland-kde.sh" else "xiaoian-x11-xfce.sh"
            val scriptPath = "/data/local/tmp/$scriptName"
            shellExecutor.run("$scriptPath -t")
            
            // Close the X11 window if it is open
            sendBroadcast(Intent("com.termux.x11.ACTION_STOP").apply {
                setPackage(packageName)
            })
            
            sessionManager.updateState(SessionState.Idle)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun lockPhone() {
        lifecycleScope.launch {
            val scriptName = if (currentDE == "kde") "xiaoian-wayland-kde.sh" else "xiaoian-x11-xfce.sh"
            val scriptPath = "/data/local/tmp/$scriptName"
            shellExecutor.run("$scriptPath -k")
            sessionManager.updateState(SessionState.Running(currentMode, currentDE, true))
            updateNotification("Session running (Phone Locked)")
        }
    }

    private fun unlockPhone() {
        // Handled by the script's watcher or we can manually kill the lock
        // For phase 1, unlocking typically requires pressing power button (script handles it)
        // If we want a button, we can send a broadcast or kill the watcher.
    }

    private fun openTerminal() {
        // Launch TerminalActivity
    }

    private fun startSessionWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val infraRoot = if (currentDE == "kde") "/data/local/xiaoian-wayland-kde" else "/data/local/xiaoian-x11-xfce"
            val script = "while true; do if [ ! -f $infraRoot/state ]; then exit 1; fi; PID=\$(cat $infraRoot/state | awk '{print \$1}'); if [ ! -d \"/proc/\$PID\" ]; then exit 1; fi; sleep 5; done"
            try {
                val process = ProcessBuilder("su", "-c", script).start()
                process.waitFor() // Blocks until the bash script exits (when the session dies)
                
                // Once the process exits, it means the session is dead
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    sessionManager.updateState(SessionState.Idle)
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = getString(R.string.notification_channel_name)
            val descriptionText = getString(R.string.notification_channel_desc)
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.sym_def_app_icon) // TODO: create a proper icon
            .setContentTitle("Xiaoian — $currentDE ($currentMode)")
            .setContentText(text)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)

        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        builder.setContentIntent(pendingIntent)

        val stopIntent = Intent(this, XiaoianService::class.java).apply { action = ACTION_STOP }
        val stopPending = PendingIntent.getService(this, 1, stopIntent, PendingIntent.FLAG_IMMUTABLE)
        builder.addAction(0, "Stop", stopPending)

        if (sessionManager.state.value is SessionState.Running && currentMode != "local") {
            val isLocked = (sessionManager.state.value as SessionState.Running).isLocked
            val lockIntent = Intent(this, XiaoianService::class.java).apply { 
                action = if (isLocked) ACTION_UNLOCK else ACTION_LOCK 
            }
            val lockPending = PendingIntent.getService(this, 2, lockIntent, PendingIntent.FLAG_IMMUTABLE)
            builder.addAction(0, if (isLocked) "Unlock" else "Lock", lockPending)
        }
        
        val prefsIntent = Intent().apply {
            setClassName(this@XiaoianService, "com.termux.x11.LoriePreferences")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val prefsPending = PendingIntent.getActivity(this, 3, prefsIntent, PendingIntent.FLAG_IMMUTABLE)
        builder.addAction(0, "Preferences", prefsPending)

        return builder.build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }
}
