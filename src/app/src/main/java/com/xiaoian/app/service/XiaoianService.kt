package com.xiaoian.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.xiaoian.app.MainActivity
import com.xiaoian.app.R
import com.xiaoian.app.shell.ScriptMessage
import com.xiaoian.app.terminal.SessionSpec
import com.xiaoian.app.terminal.TerminalActivity
import com.xiaoian.app.terminal.TerminalSessions
import com.xiaoian.app.shell.RootShell
import com.xiaoian.app.shell.ScriptOutputParser
import com.xiaoian.app.shell.ShellExecutor
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

class XiaoianService : LifecycleService() {

    companion object {
        private const val TAG = "XiaoianService"
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
    private val setupTracker = SetupProgressTracker()
    @Volatile private var lastProgressNotification = 0L

    private var audioPlayer: AudioPlayer? = null
    private var audioPlayerJob: kotlinx.coroutines.Job? = null

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
                val scriptName = ScriptEnv.scriptName(de)
                
                // Extract script from assets to INFRA_ROOT to avoid SELinux/noexec issues and allow clean uninstalls
                val infraRoot = ScriptEnv.infraRoot(de)
                val scriptPath = ScriptEnv.scriptPath(de)
                val scriptFile = File(externalCacheDir, scriptName) // temporary staging in externalCacheDir so root can read it

                // Copied on every start: a copy left over from an older app
                // version must never shadow the script bundled in this APK.
                assets.open(scriptName).use { input ->
                    scriptFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                
                // Move to INFRA_ROOT and make executable using root. No `su -c`
                // wrapper: ShellExecutor already runs inside an open root shell,
                // and nesting one here would cost a second Magisk prompt.
                shellExecutor.run("mkdir -p $infraRoot && cp ${scriptFile.absolutePath} $scriptPath && chmod +x $scriptPath")
                
                // The scripts write their downloaded assets here too, as root.
                // The app has to create it first so it stays owned by the app
                // uid -- otherwise the tarball download later cannot write.
                bootstrapManagerDownloadsDir()

                setupTracker.reset()
                sessionManager.updateSetup(setupTracker.progress)
                lastProgressNotification = 0L

                // The tool rootfs is no longer on the desktop's critical path:
                // the scripts download through the app's own Fetch and extract
                // with busybox, so nothing they do enters this chroot. Only the
                // in-app terminal uses it, so a failure here must not take the
                // desktop down with it.
                val bootstrapManager = BootstrapManager(this@XiaoianService)
                if (!bootstrapManager.isInstalled()) {
                    setupTracker.onMessage(ScriptMessage.StepStarted("Downloading bootstrap"), SystemClock.elapsedRealtime())
                    val success = bootstrapManager.installBootstrap { msg, progress ->
                        updateNotification("Bootstrap: $msg")
                        setupTracker.onMessage(ScriptMessage.AptProgress(progress * 100f, msg), SystemClock.elapsedRealtime())
                        sessionManager.updateSetup(setupTracker.progress)
                    }
                    if (success) {
                        val packages = listOf("wget", "tar", "xz-utils")
                        bootstrapManager.installPackages(packages) { msg, progress ->
                            updateNotification("Package: $msg")
                            setupTracker.onMessage(ScriptMessage.AptProgress(progress * 100f, msg), SystemClock.elapsedRealtime())
                            sessionManager.updateSetup(setupTracker.progress)
                        }
                    }
                    if (!bootstrapManager.isInstalled()) {
                        Log.w(TAG, "Tool rootfs install failed; the in-app terminal will not work, continuing with the session")
                    }
                    setupTracker.onMessage(ScriptMessage.StepDone("Downloading bootstrap"), SystemClock.elapsedRealtime())
                }
                lastProgressNotification = 0L

                // The script extracts the desktop chroot from the same tarball
                // the bootstrap uses, so it has to be on disk before the script
                // goes looking for it.
                if (!isDesktopInstalled(infraRoot)) {
                    val tarballOk = bootstrapManager.ensureRootfsTarball { msg, progress ->
                        updateNotification("Rootfs: $msg")
                        setupTracker.onMessage(ScriptMessage.AptProgress(progress * 100f, msg), SystemClock.elapsedRealtime())
                        sessionManager.updateSetup(setupTracker.progress)
                    }
                    if (!tarballOk) {
                        throw Exception("Failed to download the Debian rootfs tarball. Check logcat for details.")
                    }
                }
                lastProgressNotification = 0L

                // The script starts and supervises the display daemon itself --
                // it already has the socket-wait and teardown logic.
                //
                // It runs for minutes, so it gets a root shell of its own:
                // holding the shared one that long would block every quick
                // query the dashboard makes meanwhile. And no idle timeout --
                // an apt transaction can legitimately go quiet for a while.
                val command = "${ScriptEnv.prefix(this@XiaoianService)} $scriptPath -s --$mode"
                val sessionShell = RootShell.dedicated("session-$de")
                val result = try {
                    ShellExecutor(sessionShell).run(
                        command = command,
                        idleTimeoutMs = RootShell.NO_TIMEOUT,
                    ) { line ->
                        onScriptMessage(scriptParser.parse(line))
                    }
                } finally {
                    // The session wrapper is setsid'd away by the script, so
                    // closing this shell does not touch the running desktop.
                    sessionShell.close()
                }

                if (result.success) {
                    sessionManager.updateState(SessionState.Running(mode, de, false))
                    updateNotification("Session running")
                    if (de != "kde") {
                        audioPlayer = AudioPlayer()
                        audioPlayerJob = lifecycleScope.launch {
                            audioPlayer?.start()
                        }
                    }
                    startSessionWatchdog()
                } else {
                    // stderr is frequently useless on its own ("Terminated"),
                    // so name the step that was running when it died.
                    val step = setupTracker.progress.current?.title
                    val detail = result.error.ifEmpty { "the script exited with a non-zero status" }
                    val errMsg = if (step != null) "Failed during \"$step\": $detail" else detail
                    sessionManager.updateState(SessionState.Error(errMsg))
                    stopForeground(STOP_FOREGROUND_REMOVE)
                }
            } catch (e: Exception) {
                sessionManager.updateState(SessionState.Error(e.message ?: "Unknown error"))
                stopForeground(STOP_FOREGROUND_REMOVE)
            }
        }
    }

    /** Creates `files/downloads` under this app's uid before root touches it. */
    private fun bootstrapManagerDownloadsDir() {
        runCatching { BootstrapManager(this).rootfsTarball.parentFile?.mkdirs() }
            .onFailure { Log.w(TAG, "Could not create the downloads directory", it) }
    }

    /** True once the DE's chroot has been extracted under [infraRoot]. */
    private suspend fun isDesktopInstalled(infraRoot: String): Boolean =
        shellExecutor.run("test -x $infraRoot/debian/bin/bash").success

    /** Called on the shell reader thread for every line of the start script. */
    private fun onScriptMessage(msg: ScriptMessage) {
        val now = SystemClock.elapsedRealtime()
        if (!setupTracker.onMessage(msg, now)) return
        val progress = setupTracker.progress
        sessionManager.updateSetup(progress)

        // Android drops notification updates beyond a few per second; step
        // changes always go through, byte/apt progress at most once a second.
        val stepChanged = msg is ScriptMessage.StepStarted || msg is ScriptMessage.StepDone
        if (stepChanged || now - lastProgressNotification >= 1000) {
            lastProgressNotification = now
            updateNotification(progress.current?.title ?: "Starting session...", progress)
        }
    }

    private fun stopSession() {
        sessionManager.updateState(SessionState.Stopping)
        updateNotification("Stopping session...")
        lifecycleScope.launch {
            audioPlayerJob?.cancel()
            audioPlayer?.stop()
            
            // Any terminal in this desktop's chroot has to go first. Its
            // processes are chroot processes like any other, so the script's
            // unmount_all() would find the mounts still busy, report
            // "[!] WARNING: mount(s) still present" and fail the stop.
            TerminalSessions.closeAllFor(this@XiaoianService, currentDE)

            val scriptPath = ScriptEnv.scriptPath(currentDE)
            // Teardown kills chroot processes and unmounts; it can sit quiet
            // for a while, so it gets its own shell and no idle timeout.
            val stopShell = RootShell.dedicated("stop-$currentDE")
            try {
                ShellExecutor(stopShell).run(
                    "${ScriptEnv.prefix(this@XiaoianService)} $scriptPath -t",
                    idleTimeoutMs = RootShell.NO_TIMEOUT,
                )
            } finally {
                stopShell.close()
            }

            closeFrontend()

            sessionManager.updateState(SessionState.Idle)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun lockPhone() {
        lifecycleScope.launch {
            val scriptPath = ScriptEnv.scriptPath(currentDE)
            shellExecutor.run("${ScriptEnv.prefix(this@XiaoianService)} $scriptPath -k")
            sessionManager.updateState(SessionState.Running(currentMode, currentDE, true))
            updateNotification("Session running (Phone Locked)")
        }
    }

    private fun unlockPhone() {
        // Handled by the script's watcher or we can manually kill the lock
        // For phase 1, unlocking typically requires pressing power button (script handles it)
        // If we want a button, we can send a broadcast or kill the watcher.
    }

    /** The notification's terminal action: a shell in the running desktop's chroot. */
    private fun openTerminal() {
        val intent = TerminalActivity
            .intent(this, SessionSpec.DesktopChroot(currentDE))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
    }

    /**
     * Closes whichever desktop frontend is on screen.
     *
     * Called for every way a session can end -- the Stop button, a logout from
     * inside the desktop, a crash -- because otherwise a dead surface stays on
     * the external display with no way to dismiss it.
     *
     * Termux:X11 is closed through its own ACTION_STOP broadcast (which
     * `LorieApp` turns into `finishAndRemoveTask`); Anland has no such
     * receiver, so its activity is finished directly through the static
     * reference it keeps.
     */
    private fun closeFrontend() {
        runCatching {
            sendBroadcast(Intent("com.termux.x11.ACTION_STOP").apply {
                setPackage(packageName)
            })
        }.onFailure { Log.w(TAG, "Could not stop the X11 frontend", it) }

        runCatching {
            com.anland.termux.MainActivity.sInstance?.let { activity ->
                activity.runOnUiThread { activity.finishAndRemoveTask() }
            }
        }.onFailure { Log.w(TAG, "Could not close the Anland frontend", it) }
    }

    private fun startSessionWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val stateFile = "${ScriptEnv.infraRoot(currentDE)}/state"
            // One cheap check every five seconds on the shared root shell. This
            // used to be a `while true` loop inside a root shell of its own --
            // a whole extra Magisk prompt just to watch a file.
            val alive = "p=\$(awk '{print \$1}' $stateFile 2>/dev/null); " +
                "[ -n \"\$p\" ] && [ -d \"/proc/\$p\" ]"
            try {
                while (isActive) {
                    kotlinx.coroutines.delay(5_000)
                    if (!shellExecutor.run(alive).success) break
                }
                
                // Once the process exits, it means the session is dead
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    // The sound server died with the session; the reader would
                    // otherwise keep retrying a socket nobody listens on.
                    audioPlayerJob?.cancel()
                    audioPlayer?.stop()
                    audioPlayer = null
                    closeFrontend()
                    // The desktop's own teardown already killed everything in
                    // its chroot, terminals included, so this is bookkeeping
                    // rather than prevention -- but without it the store would
                    // keep listing sessions whose shells are long gone.
                    TerminalSessions.closeAllFor(this@XiaoianService, currentDE)
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

    private fun buildNotification(text: String, setup: SetupProgress? = null): Notification {
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Xiaoian — $currentDE ($currentMode)")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)

        if (setup != null) {
            val fraction = setup.fraction
            builder.setProgress(100, ((fraction ?: 0f) * 100).toInt(), fraction == null)
        }

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
        
        // Each frontend has its own settings screen. This was hard-coded to
        // the X11 one, so a KDE session opened Termux:X11's preferences.
        val prefsActivity = if (currentDE == "kde")
            "com.anland.termux.SettingsActivity"
        else
            "com.termux.x11.LoriePreferences"
        val prefsIntent = Intent().apply {
            setClassName(this@XiaoianService, prefsActivity)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val prefsPending = PendingIntent.getActivity(this, 3, prefsIntent, PendingIntent.FLAG_IMMUTABLE)
        builder.addAction(0, "Preferences", prefsPending)

        return builder.build()
    }

    private fun updateNotification(text: String, setup: SetupProgress? = null) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(text, setup))
    }
}
