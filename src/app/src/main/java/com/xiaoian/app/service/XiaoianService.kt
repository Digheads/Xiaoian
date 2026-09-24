package com.xiaoian.app.service

import android.content.Intent
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.xiaoian.app.model.Desktop
import com.xiaoian.app.model.DisplayMode
import com.xiaoian.app.shell.RootShell
import com.xiaoian.app.shell.ScriptMessage
import com.xiaoian.app.shell.ScriptOutputParser
import com.xiaoian.app.shell.ShellExecutor
import com.xiaoian.app.terminal.SessionSpec
import com.xiaoian.app.terminal.TerminalActivity
import com.xiaoian.app.terminal.TerminalSessions
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
        const val ACTION_RETARGET = "com.xiaoian.app.RETARGET"
        const val ACTION_ADOPT = "com.xiaoian.app.ADOPT"
    }

    val sessionManager = SessionManagerProvider.sessionManager
    private val shellExecutor = ShellExecutor()
    private val scriptParser = ScriptOutputParser()
    private val setupTracker = SetupProgressTracker()
    @Volatile private var lastProgressNotification = 0L

    private val notifications = SessionNotificationController(this)
    private val frontends = FrontendController(this)
    private val watchdog = SessionWatchdog(lifecycleScope, shellExecutor)

    private var audioPlayer: AudioPlayer? = null
    private var audioPlayerJob: kotlinx.coroutines.Job? = null

    private var currentMode: DisplayMode = DisplayMode.DEFAULT
    private var currentDE: Desktop = Desktop.DEFAULT

    override fun onCreate() {
        super.onCreate()
        notifications.createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START -> startSession(
                mode = DisplayMode.fromId(intent.getStringExtra("mode")),
                de = Desktop.fromId(intent.getStringExtra("de")),
                // -1 means "not chosen": one display, local mode, or a caller
                // that does not know about the picker.
                displayId = intent.getIntExtra("displayId", -1).takeIf { it >= 0 },
                displaySize = intent.getStringExtra("displaySize"),
                password = intent.getStringExtra("password"),
            )
            ACTION_STOP -> stopSession()
            ACTION_LOCK -> lockPhone()
            ACTION_UNLOCK -> unlockPhone()
            ACTION_TERMINAL -> openTerminal()
            ACTION_ADOPT -> adoptSession(
                mode = intent.getStringExtra("mode")?.let(DisplayMode::fromId) ?: DisplayMode.LOCAL,
                de = intent.getStringExtra("de")?.let(Desktop::fromId) ?: return START_STICKY,
                locked = intent.getBooleanExtra("locked", false),
            )
            ACTION_RETARGET -> retargetSession(
                mode = intent.getStringExtra("mode")?.let(DisplayMode::fromId) ?: return START_STICKY,
                displayId = intent.getIntExtra("displayId", -1).takeIf { it >= 0 },
                displaySize = intent.getStringExtra("displaySize"),
            )
        }
        return START_STICKY
    }

    private fun startSession(
        mode: DisplayMode,
        de: Desktop,
        displayId: Int? = null,
        displaySize: String? = null,
        password: String? = null,
    ) {
        // Not re-entrant: two rapid START intents would otherwise run two
        // installs against the same setupTracker and two session shells. The
        // dashboard disables START while busy, but the service cannot trust it.
        val state = sessionManager.state.value
        if (state !is SessionState.Idle && state !is SessionState.Error) return
        currentMode = mode
        currentDE = de
        sessionManager.updateState(SessionState.Starting)
        startForeground(NOTIFICATION_ID, notifications.build(currentDE, currentMode, sessionManager.state.value, "Starting session...", null))

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

                // Files the script uses from beside itself, refreshed the same
                // way and for the same reason as the script.
                for (asset in de.extraAssets) {
                    val staged = File(externalCacheDir, asset)
                    assets.open(asset).use { input ->
                        staged.outputStream().use { output -> input.copyTo(output) }
                    }
                    shellExecutor.run("cp ${staged.absolutePath} $infraRoot/$asset")
                }
                
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
                // First start only (the dashboard asks): chpasswd input,
                // owner-only in filesDir, for the script to consume and delete.
                val passwordFile = password?.let {
                    File(filesDir, "root-password-${de.id}").apply {
                        writeText("root:$it\n")
                        setReadable(false, false); setReadable(true, true)
                    }
                }
                val env = ScriptEnv.prefix(this@XiaoianService, displayId, displaySize, passwordFile)
                val command = "$env $scriptPath -s --${mode.id}"
                val sessionShell = RootShell.dedicated("session-${de.id}")
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
                    // Normally already gone; a start that failed early did
                    // not get as far as the chpasswd step.
                    passwordFile?.delete()
                }

                if (result.success) {
                    sessionManager.updateState(SessionState.Running(mode, de, false))
                    updateNotification("Session running")
                    attachToSession(de)
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

    /** Sound and the watchdog: everything a running session needs from the app. */
    private fun attachToSession(de: Desktop) {
        if (de != Desktop.KDE) {
            audioPlayer = AudioPlayer()
            audioPlayerJob = lifecycleScope.launch {
                audioPlayer?.start()
            }
        }
        watchdog.start(
            de = de,
            onLockChanged = { locked -> setLocked(locked) },
            onDied = {
                // The sound server died with the session; the reader would
                // otherwise keep retrying a socket nobody listens on.
                audioPlayerJob?.cancel()
                audioPlayer?.stop()
                audioPlayer = null
                frontends.close()
                // The desktop's own teardown normally killed its chroot
                // terminals already; this catches any it missed. Either way
                // their tabs stay, struck through, so a logout or crash does
                // not take their output with it.
                TerminalSessions.endAllFor(this@XiaoianService, currentDE)
                sessionManager.updateState(SessionState.Idle)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            },
        )
    }

    /**
     * A desktop that outlived the app's process -- a reinstall, or Android
     * killing the app -- keeps running on its own, but the app came back as
     * Idle: no stop button, no watchdog, and delete refused by the script.
     * The dashboard finds it (see DashboardViewModel) and hands it over here.
     */
    private fun adoptSession(mode: DisplayMode, de: Desktop, locked: Boolean) {
        if (sessionManager.state.value is SessionState.Idle) {
            currentMode = mode
            currentDE = de
            sessionManager.updateState(SessionState.Running(mode, de, locked))
        }
        // Started with startForegroundService: this is owed either way.
        startForeground(NOTIFICATION_ID, notifications.build(currentDE, currentMode, sessionManager.state.value, "Session running", null))
        if (!watchdog.isActive) attachToSession(de)
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
        val state = sessionManager.state.value
        if (state is SessionState.Idle || state is SessionState.Stopping) return
        sessionManager.updateState(SessionState.Stopping)
        updateNotification("Stopping session...")
        lifecycleScope.launch {
            audioPlayerJob?.cancel()
            audioPlayer?.stop()
            
            // Any terminal in this desktop's chroot has to go first. Its
            // processes are chroot processes like any other, so the script's
            // unmount_all() would find the mounts still busy, report
            // "[!] WARNING: mount(s) still present" and fail the stop.
            TerminalSessions.endAllFor(this@XiaoianService, currentDE)

            val scriptPath = ScriptEnv.scriptPath(currentDE)
            // Teardown kills chroot processes and unmounts; it can sit quiet
            // for a while, so it gets its own shell and no idle timeout.
            val stopShell = RootShell.dedicated("stop-${currentDE.id}")
            try {
                ShellExecutor(stopShell).run(
                    "${ScriptEnv.prefix(this@XiaoianService)} $scriptPath -t",
                    idleTimeoutMs = RootShell.NO_TIMEOUT,
                )
            } finally {
                stopShell.close()
            }

            frontends.close()

            sessionManager.updateState(SessionState.Idle)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    /**
     * Switches a *running* session to a different mode without a full
     * stop/start, by closing the desktop window and reopening it where the
     * new mode wants it.
     *
     * Three steps, in this order:
     *  1. `-r`: the script checks the reboot-gated global settings mirror
     *     needs, swaps `wm size` for mirror, and records the new mode. A
     *     refusal stops here, with the session untouched.
     *  2. [closeFrontend], then wait for the old window to be destroyed.
     *     Both frontends are singleInstance: an `am start` while the old
     *     one is still open only brings it forward on the display it is
     *     already on, and closing it afterwards closed the new one too.
     *  3. `--relaunch`: a fresh `am start` on the display the mode calls for.
     */
    private fun retargetSession(mode: DisplayMode, displayId: Int? = null, displaySize: String? = null) {
        val running = sessionManager.state.value as? SessionState.Running ?: return
        if (mode == currentMode) return

        sessionManager.updateRetargetError(null)
        sessionManager.updateRetargeting(true)
        lifecycleScope.launch {
            try {
                val scriptPath = ScriptEnv.scriptPath(currentDE)
                val env = ScriptEnv.prefix(this@XiaoianService, displayId, displaySize)
                val result = shellExecutor.run("$env $scriptPath -r --${mode.id}")
                if (!result.success) {
                    sessionManager.updateRetargetError(cleanScriptError(result.error, "Could not switch mode"))
                    return@launch
                }
                currentMode = mode
                sessionManager.updateState(SessionState.Running(mode, currentDE, running.isLocked))
                updateNotification("Session running")

                frontends.close()
                frontends.waitForClosed()

                val relaunch = shellExecutor.run("$env $scriptPath --relaunch")
                if (!relaunch.success) {
                    sessionManager.updateRetargetError(
                        cleanScriptError(relaunch.error, "Switched, but the desktop window did not open") +
                            "\nUse OPEN DESKTOP to open it."
                    )
                }
            } finally {
                sessionManager.updateRetargeting(false)
            }
        }
    }

    /** The script's "[!]" lines without the marker, for display on a card. */
    private fun cleanScriptError(error: String, fallback: String): String =
        error.lines()
            .map { it.trim().removePrefix("[!]").trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
            .ifEmpty { fallback }

    private fun lockPhone() {
        lifecycleScope.launch {
            val scriptPath = ScriptEnv.scriptPath(currentDE)
            if (shellExecutor.run("${ScriptEnv.prefix(this@XiaoianService)} $scriptPath -k").success) {
                setLocked(true)
            }
        }
    }

    /** The notification's Unlock action; volume down twice does the same. */
    private fun unlockPhone() {
        lifecycleScope.launch {
            val scriptPath = ScriptEnv.scriptPath(currentDE)
            shellExecutor.run("${ScriptEnv.prefix(this@XiaoianService)} $scriptPath --unlock")
            setLocked(false)
        }
    }

    /**
     * The lock is mostly undone by the script's own watcher, which the app
     * never hears from, so the watchdog calls this too: otherwise the
     * notification kept offering "Unlock" long after the phone was unlocked.
     */
    private fun setLocked(locked: Boolean) {
        val running = sessionManager.state.value as? SessionState.Running ?: return
        if (running.isLocked == locked) return
        sessionManager.updateState(running.copy(isLocked = locked))
        updateNotification(if (locked) "Session running (Phone Locked)" else "Session running")
    }

    /** The notification's terminal action: a shell in the running desktop's chroot. */
    private fun openTerminal() {
        val intent = TerminalActivity
            .intent(this, SessionSpec.DesktopChroot(currentDE))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
    }

    private fun updateNotification(text: String, setup: SetupProgress? = null) {
        notifications.show(currentDE, currentMode, sessionManager.state.value, text, setup)
    }
}
