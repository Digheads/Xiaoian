# 06 — Session Management & Android Service

## Overview

The Xiaoian app needs a `Foreground Service` to:
1. Keep the desktop session alive when the app is in the background
2. Provide persistent notification with quick actions
3. Manage the session lifecycle (start → run → stop)
4. Handle Android system events (boot, battery, display changes)

## Session State Machine

```
                    ┌─────────────────────────────────────┐
                    │                                     │
                    ▼                                     │
┌──────┐    ┌────────────┐    ┌──────────┐    ┌────────┐ │
│ IDLE │───►│ INSTALLING │───►│ STARTING │───►│RUNNING │─┘
│      │    │            │    │          │    │        │
└──────┘    └─────┬──────┘    └────┬─────┘    └───┬────┘
   ▲              │                │              │
   │              │ error          │ error        │ stop/crash
   │              ▼                ▼              ▼
   │         ┌─────────┐    ┌──────────┐    ┌──────────┐
   └─────────│  ERROR  │    │  ERROR   │    │ STOPPING │
             └─────────┘    └──────────┘    └────┬─────┘
                                                  │
                                                  ▼
                                              ┌──────┐
                                              │ IDLE │
                                              └──────┘
```

### Sub-states of RUNNING

```
RUNNING
├── UNLOCKED     ← phone screen is normal
├── LOCKED       ← phone screen is off, desktop on external display
└── mode: extend | mirror | local
```

## XiaoianService

```kotlin
class XiaoianService : Service() {

    companion object {
        const val NOTIFICATION_ID = 1
        const val CHANNEL_ID = "xiaoian_session"

        // Actions
        const val ACTION_START = "com.xiaoian.app.START"
        const val ACTION_STOP = "com.xiaoian.app.STOP"
        const val ACTION_LOCK = "com.xiaoian.app.LOCK"
        const val ACTION_UNLOCK = "com.xiaoian.app.UNLOCK"
        const val ACTION_TERMINAL = "com.xiaoian.app.TERMINAL"
    }

    private val sessionManager = SessionManager()
    private val shellExecutor = ShellExecutor()
    private val displayDetector = DisplayDetector(this)

    private var currentState: SessionState = SessionState.IDLE
    private var currentMode: String = "extend"
    private var currentDE: String = "kde"

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
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
        updateState(SessionState.STARTING)
        startForeground(NOTIFICATION_ID, buildNotification())

        serviceScope.launch {
            try {
                // 1. Ensure bootstrap is ready
                updateState(SessionState.INSTALLING)
                bootstrapManager.ensureReady { progress ->
                    updateNotification("Setting up: $progress")
                }

                // 2. Run the shell script
                updateState(SessionState.STARTING)
                val script = if (de == "kde")
                    "xiaoian-wayland-kde.sh" else "xiaoian-x11-xfce.sh"

                val result = shellExecutor.run(
                    command = "su -c 'XIAOIAN_PREFIX=$prefix ./$script -s --$mode'",
                    onOutput = { line -> parseScriptOutput(line) }
                )

                if (result.success) {
                    updateState(SessionState.RUNNING)
                    startSessionWatchdog()
                } else {
                    updateState(SessionState.ERROR(result.stderr))
                }
            } catch (e: Exception) {
                updateState(SessionState.ERROR(e.message))
            }
        }
    }

    private fun stopSession() {
        updateState(SessionState.STOPPING)
        serviceScope.launch {
            val script = if (currentDE == "kde")
                "xiaoian-wayland-kde.sh" else "xiaoian-x11-xfce.sh"
            shellExecutor.run("su -c './$script -t'")
            updateState(SessionState.IDLE)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }
}
```

## Persistent Notification

```
┌──────────────────────────────────────────────────────────┐
│ 🖥  Xiaoian — KDE Plasma 6 running (extend mode)        │
│    Session uptime: 2h 34m • External display: HDMI-1     │
│                                                          │
│  ┌────────┐  ┌────────┐  ┌──────────┐  ┌──────────────┐│
│  │  Stop  │  │  Lock  │  │ Terminal │  │ Open Desktop ││
│  └────────┘  └────────┘  └──────────┘  └──────────────┘│
└──────────────────────────────────────────────────────────┘
```

```kotlin
private fun buildNotification(): Notification {
    val channel = NotificationChannel(
        CHANNEL_ID, "Xiaoian Session",
        NotificationManager.IMPORTANCE_LOW  // no sound, persistent
    )
    notificationManager.createNotificationChannel(channel)

    val builder = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_desktop)
        .setContentTitle("Xiaoian — ${deDisplayName()} running ($currentMode mode)")
        .setContentText("Session uptime: ${formatUptime()}")
        .setOngoing(true)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)

    // Action: Stop
    builder.addAction(
        R.drawable.ic_stop, "Stop",
        PendingIntent.getService(this, 0,
            Intent(this, XiaoianService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE
        )
    )

    // Action: Lock / Unlock
    if (currentState is SessionState.Running) {
        val isLocked = (currentState as SessionState.Running).isLocked
        builder.addAction(
            if (isLocked) R.drawable.ic_unlock else R.drawable.ic_lock,
            if (isLocked) "Unlock" else "Lock",
            PendingIntent.getService(this, 1,
                Intent(this, XiaoianService::class.java).apply {
                    action = if (isLocked) ACTION_UNLOCK else ACTION_LOCK
                },
                PendingIntent.FLAG_IMMUTABLE
            )
        )
    }

    // Action: Terminal
    builder.addAction(
        R.drawable.ic_terminal, "Terminal",
        PendingIntent.getActivity(this, 2,
            Intent(this, TerminalActivity::class.java).apply {
                putExtra("session_type", "chroot")
            },
            PendingIntent.FLAG_IMMUTABLE
        )
    )

    return builder.build()
}
```

## Boot Receiver

```kotlin
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val prefs = context.getSharedPreferences("xiaoian", MODE_PRIVATE)
        if (!prefs.getBoolean("auto_start_on_boot", false)) return

        val mode = prefs.getString("default_mode", "extend") ?: "extend"
        val de = prefs.getString("default_de", "kde") ?: "kde"

        val serviceIntent = Intent(context, XiaoianService::class.java).apply {
            action = XiaoianService.ACTION_START
            putExtra("mode", mode)
            putExtra("de", de)
        }
        context.startForegroundService(serviceIntent)
    }
}
```

AndroidManifest.xml:
```xml
<receiver android:name=".BootReceiver"
          android:enabled="true"
          android:exported="true">
    <intent-filter>
        <action android:name="android.intent.action.BOOT_COMPLETED" />
    </intent-filter>
</receiver>
```

## Session Watchdog

The service monitors the session's health:

```kotlin
private fun startSessionWatchdog() {
    watchdogJob = serviceScope.launch {
        while (isActive) {
            delay(5000) // Check every 5 seconds

            val stateFile = File("$infraRoot/state")
            if (!stateFile.exists()) {
                // Session ended (wrapper exited, state file cleaned up)
                updateState(SessionState.IDLE)
                break
            }

            // Read state file: "<pid> <boot_id>"
            val (pid, bootId) = stateFile.readText().trim().split(" ")
            val procDir = File("/proc/$pid")

            if (!procDir.exists()) {
                // Process died without cleanup
                updateState(SessionState.ERROR("Session crashed"))
                // Trigger cleanup
                shellExecutor.run("su -c './$scriptName -t'")
                break
            }

            // Update notification with uptime
            updateNotification()
        }
    }
}
```

## Script Output Parser

The shell scripts output structured messages that the app can parse:

```kotlin
class ScriptOutputParser {

    sealed class ScriptMessage {
        data class Info(val text: String) : ScriptMessage()      // [*]
        data class Warning(val text: String) : ScriptMessage()   // [!] WARNING
        data class Error(val text: String) : ScriptMessage()     // [!] ERROR
        data class Progress(val step: String) : ScriptMessage()  // Detected patterns
    }

    fun parse(line: String): ScriptMessage {
        return when {
            line.startsWith("[*]") -> {
                val text = line.removePrefix("[*]").trim()
                when {
                    text.startsWith("Downloading") -> Progress(text)
                    text.startsWith("Installing") -> Progress(text)
                    text.startsWith("Extracting") -> Progress(text)
                    text.startsWith("Booting") -> Progress(text)
                    else -> Info(text)
                }
            }
            line.startsWith("[!] ERROR") -> Error(line.removePrefix("[!] ERROR:").trim())
            line.startsWith("[!] WARNING") -> Warning(line.removePrefix("[!] WARNING:").trim())
            line.startsWith("[!]") -> Warning(line.removePrefix("[!]").trim())
            else -> Info(line)
        }
    }
}
```

## Estimated Effort

| Component | Time |
|---|---|
| XiaoianService (foreground service, lifecycle) | 3–4 days |
| Session state machine | 2–3 days |
| Persistent notification with actions | 2–3 days |
| Boot receiver | 1 day |
| Session watchdog | 1–2 days |
| Script output parser | 1–2 days |
| Lock/Unlock via service | 1–2 days |
| **Total** | **~2 weeks** |
