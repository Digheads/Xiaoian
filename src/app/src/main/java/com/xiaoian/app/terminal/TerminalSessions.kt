package com.xiaoian.app.terminal

import android.content.Context
import android.util.Log
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.terminal.XiaoianPty
import com.xiaoian.app.service.BootstrapManager
import com.xiaoian.app.shell.RootShell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** One open terminal, as the store and the UI see it. */
class XiaoianSession internal constructor(
    val id: Int,
    val spec: SessionSpec,
    internal val pty: RootPty,
    val terminal: TerminalSession,
    name: String,
) {
    /**
     * Tab caption. Deliberately *not* the terminal's own title: bash sets that
     * from the prompt through an escape sequence, so it turns into something
     * like `root@localhost: /root` the moment the shell starts, which is both
     * unreadable in a tab and different every time you cd. This starts as the
     * environment's name and only changes when the user renames it.
     */
    var title: String = name
        internal set

    /** False once the shell has exited; the tab stays until the user closes it. */
    val isRunning: Boolean get() = terminal.isRunning
}

/**
 * Every open terminal session in this process.
 *
 * Deliberately process-scoped rather than owned by the Activity: a session has
 * to survive rotation, and the [TerminalSessionClient] has to outlive the view
 * too -- a session whose client went away with the Activity would NPE in
 * `notifyScreenUpdate()` the moment its shell wrote anything.
 *
 * It does not survive the process being killed. What that leaves behind is
 * handled by [sweepOrphans].
 */
object TerminalSessions {

    private const val TAG = "TerminalSessions"

    /** Open session ids, so a killed process can be cleaned up after. */
    private const val OPEN_LIST = "terminal/open-sessions"

    /**
     * The file [rememberOpen] writes, one session id per line.
     *
     * Also read by the desktop start scripts, which have to tell an in-app
     * terminal apart from a crashed session's leftovers before they clear the
     * chroot out -- see `XIAOIAN_OPEN_SESSIONS` in `ScriptEnv`.
     */
    fun openListFile(context: Context): File = File(context.filesDir, OPEN_LIST)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val counter = AtomicInteger()

    private val _sessions = MutableStateFlow<List<XiaoianSession>>(emptyList())
    val sessions: StateFlow<List<XiaoianSession>> = _sessions.asStateFlow()

    /**
     * The visible terminal screen, while there is one. Set on resume, cleared
     * on pause -- holding it any longer leaks the Activity.
     */
    var listener: TerminalSessionClient? = null

    /**
     * Application context, so a session that ends by itself can be forgotten
     * without one being handed in. Set by the first [open].
     */
    private var appContext: Context? = null

    /** Opens a session. Starting the shell blocks, so this is not main-thread work. */
    suspend fun open(context: Context, spec: SessionSpec): Result<XiaoianSession> {
        val ctx = context.applicationContext
        appContext = ctx

        val pty = withContext(Dispatchers.IO) { RootPty.start(ctx, spec) }
            .getOrElse { return Result.failure(it) }

        // TerminalSession builds its main-thread Handler in the constructor,
        // from whatever thread runs it, so this half cannot stay on the IO
        // dispatcher -- it throws "Can't create handler inside thread ... that
        // has not called Looper.prepare()".
        return withContext(Dispatchers.Main) {
            val terminal = TerminalSession(null, null, null, null, null, StoreClient())
            terminal.mSessionName = spec.label
            // The pty already exists and already has a root process on it; the
            // session only has to adopt them.
            terminal.setPtyStarter(Starter(pty))
            val session = XiaoianSession(
                counter.incrementAndGet(), spec, pty, terminal, defaultName(spec),
            )
            _sessions.value = _sessions.value + session
            rememberOpen(ctx)
            Result.success(session)
        }
    }

    /** Longest tab caption the rename dialog accepts. */
    const val MAX_NAME_LENGTH = 15

    /**
     * Renames a tab. Blank restores the default, so there is a way back without
     * a separate button.
     */
    fun rename(session: XiaoianSession, name: String) {
        session.title = name.trim().take(MAX_NAME_LENGTH).ifBlank { defaultName(session.spec) }
        _sessions.value = _sessions.value.toList()
    }

    /**
     * "XIAOIAN", then "XIAOIAN 2" and so on. Two tabs reading the same word with no
     * way to tell them apart is worse than a number nobody asked for.
     */
    private fun defaultName(spec: SessionSpec): String {
        val taken = _sessions.value.count { it.spec == spec }
        return if (taken == 0) spec.label else "${spec.label} ${taken + 1}"
    }

    /** Ends a session and forgets it. Returns once the processes are gone. */
    suspend fun close(context: Context, session: XiaoianSession) = withContext(Dispatchers.IO) {
        val everStarted = session.terminal.emulator != null
        RootPty.killSession(session.pty.sid)
        // A session whose view never laid out never adopted the fd, so nobody
        // else will close it.
        forget(context.applicationContext, session, closeMaster = !everStarted)
    }

    /**
     * Ends every session in [group] -- see [SessionSpec.group].
     *
     * This has to run before a desktop is stopped and before the tool rootfs is
     * wiped, and in both cases for correctness rather than tidiness: a live
     * chroot shell makes the desktop script's `unmount_all()` report mounts
     * still present and fail the stop, and an `rm -rf` over a live `--bind
     * /dev` walks into it and deletes the host's device nodes.
     */
    suspend fun closeGroup(context: Context, group: String) {
        _sessions.value.filter { it.spec.group == group }.forEach { close(context, it) }
    }

    suspend fun closeAllFor(context: Context, de: String) =
        closeGroup(context, SessionSpec.DesktopChroot(de).group)

    /**
     * Like [closeAllFor], but the tabs stay: every shell in the desktop's
     * chroot is killed -- the stop needs them gone just as much -- and each
     * tab is then left struck through by [StoreClient.onSessionFinished], with
     * its output still there to read. A logout or crash should not take away
     * what someone was in the middle of reading.
     *
     * A session whose view never laid out has no emulator, so nothing would
     * ever mark it finished; that one is closed outright.
     */
    suspend fun endAllFor(context: Context, de: String) = withContext(Dispatchers.IO) {
        val group = SessionSpec.DesktopChroot(de).group
        _sessions.value.filter { it.spec.group == group && it.isRunning }.forEach { session ->
            if (session.terminal.emulator == null) close(context, session)
            else RootPty.killSession(session.pty.sid)
        }
    }

    suspend fun closeAllLocal(context: Context) =
        closeGroup(context, SessionSpec.Local.group)

    /**
     * Kills sessions left over from a process that Android killed.
     *
     * Their pty master went with the process, so nothing can ever attach to
     * them again; most shells take the resulting SIGHUP and exit on their own,
     * but anything that ignores it would sit in the chroot forever and block
     * the next unmount. Returns how many were still alive, for the UI to
     * mention once.
     *
     * (The plan had this offering the user a choice. It kills instead: by then
     * the sessions are unreachable, so there is nothing to choose between, and
     * leaving them is the failure that once froze the phone.)
     */
    suspend fun sweepOrphans(context: Context): Int = withContext(Dispatchers.IO) {
        val ctx = context.applicationContext
        appContext = ctx
        // Nothing is open in a process that has just started, so this also
        // clears an Android bind left behind by one Android killed.
        releaseAndroidBind(ctx)

        val file = openListFile(ctx)
        val recorded = runCatching { file.readLines() }.getOrNull().orEmpty()
            .mapNotNull { it.trim().toIntOrNull() }
        if (recorded.isEmpty()) return@withContext 0

        val live = recorded.filter { sid ->
            RootShell.shared.execBlocking(hasSession(sid), idleTimeoutMs = 20_000L).exitCode == 0
        }
        live.forEach { RootPty.killSession(it) }
        rememberOpen(ctx)
        if (live.isNotEmpty()) Log.w(TAG, "killed ${live.size} orphaned session(s): $live")
        live.size
    }

    // ------------------------------------------------------------- internals

    /** Exit 0 when any process is still in session [sid]. Same field 4 of /proc/N/stat as RootPty. */
    private fun hasSession(sid: Int): String = """
        (
        for d in /proc/[0-9]*; do
            read -r line < "${'$'}d/stat" 2>/dev/null || continue
            rest=${'$'}{line##*\) }
            set -- ${'$'}rest
            [ "${'$'}4" = "$sid" ] && exit 0
        done
        exit 1
        )
    """.trimIndent()

    private fun forget(context: Context, session: XiaoianSession, closeMaster: Boolean) {
        if (_sessions.value.none { it === session }) return
        _sessions.value = _sessions.value.filterNot { it === session }
        session.pty.cleanup(closeMaster)
        rememberOpen(context)
        releaseAndroidBind(context)
    }

    /**
     * Unmounts the local session's view of the Android filesystem once no live
     * local session is left.
     *
     * Unlike /proc, /sys and /dev, this one is not cheap to leave behind: it is
     * a recursive bind of `/`, so it puts ~190 entries in /proc/mounts, and
     * because /data is shared the same set propagates into Android's data
     * mirror -- roughly 400 lines for one terminal the user has finished with.
     *
     * A finished-but-still-listed tab does not count: its shell is gone, so it
     * has nothing to look at any more.
     */
    private fun releaseAndroidBind(context: Context) {
        if (_sessions.value.any { it.spec is SessionSpec.Local && it.isRunning }) return
        // filesDir is ours, so this costs nothing; the mount point itself sits
        // inside a root-owned tree, so asking about it is not worth the risk of
        // a stat that fails for the wrong reason and skips the cleanup.
        val prefix = BootstrapManager(context).prefixDir
        if (!prefix.exists()) return
        val target = File(prefix, SessionScripts.ANDROID_MOUNT).absolutePath
        scope.launch {
            val result = RootShell.shared.execBlocking(
                SessionScripts.unmountTree(target), idleTimeoutMs = 60_000L,
            )
            if (!result.success) Log.w(TAG, "could not unmount $target: ${result.stderr}")
        }
    }

    /**
     * Records the session ids that still have processes behind them, for
     * [sweepOrphans] to find if this process is killed. A finished session
     * still has a tab, but nothing left to clean up.
     */
    private fun rememberOpen(context: Context) {
        val file = openListFile(context)
        runCatching {
            file.parentFile?.mkdirs()
            val live = _sessions.value.filter { it.isRunning }.map { it.pty.sid.toString() }
            file.writeText(live.joinToString(System.lineSeparator()))
        }
    }

    /** Hands the already-made pty to a [TerminalSession]. */
    private class Starter(private val pty: RootPty) : TerminalSession.PtyStarter {
        override fun start(
            processId: IntArray,
            rows: Int,
            columns: Int,
            cellWidthPixels: Int,
            cellHeightPixels: Int,
        ): Int {
            processId[0] = pty.sid
            // A plain ioctl on the master: no root, no round trip, so this is
            // safe to run on the main thread where initializeEmulator() lives.
            XiaoianPty.setWindowSize(pty.masterFd, rows, columns, cellWidthPixels, cellHeightPixels)
            return pty.masterFd
        }

        override fun stop(processId: Int) {
            // Called on the main thread from finishIfRunning(). The pid handed
            // in is whatever the session recorded, which is 0 before the first
            // layout; the pty's own sid is always right.
            val sid = pty.sid
            scope.launch { RootPty.killSession(sid) }
        }
    }

    /**
     * The client every session keeps. It belongs to the store, not to whatever
     * screen happens to be showing, and forwards to [listener] when one is.
     */
    private class StoreClient : TerminalSessionClient {
        override fun onTextChanged(changedSession: TerminalSession) {
            listener?.onTextChanged(changedSession)
        }

        override fun onTitleChanged(changedSession: TerminalSession) {
            // Only forwarded: the tab caption is ours, see XiaoianSession.title.
            listener?.onTitleChanged(changedSession)
        }

        override fun onSessionFinished(finishedSession: TerminalSession) {
            listener?.onSessionFinished(finishedSession)
            // The shell exited by itself: `exit`, or the script refused because
            // nothing is installed, or the desktop was stopped underneath it.
            // The tab deliberately stays, so whatever it printed last -- the
            // reason, and "[Process completed - press Enter]" -- is still there
            // to read. It just stops counting as open, or the next orphan sweep
            // would chase a dead session id, and a recycled one at that.
            if (_sessions.value.none { it.terminal === finishedSession }) return
            _sessions.value = _sessions.value.toList()
            appContext?.let {
                rememberOpen(it)
                releaseAndroidBind(it)
            }
        }

        override fun onCopyTextToClipboard(session: TerminalSession, text: String?) {
            listener?.onCopyTextToClipboard(session, text)
        }

        override fun onPasteTextFromClipboard(session: TerminalSession?) {
            listener?.onPasteTextFromClipboard(session)
        }

        override fun onBell(session: TerminalSession) {
            listener?.onBell(session)
        }

        override fun onColorsChanged(session: TerminalSession) {
            listener?.onColorsChanged(session)
        }

        override fun onTerminalCursorStateChange(state: Boolean) {
            listener?.onTerminalCursorStateChange(state)
        }

        override fun setTerminalShellPid(session: TerminalSession, pid: Int) {
            listener?.setTerminalShellPid(session, pid)
        }

        /** null means the emulator's default block cursor. */
        override fun getTerminalCursorStyle(): Int? = null

        override fun logError(tag: String?, message: String?) { Log.e(tag ?: TAG, message ?: "") }
        override fun logWarn(tag: String?, message: String?) { Log.w(tag ?: TAG, message ?: "") }
        override fun logInfo(tag: String?, message: String?) { Log.i(tag ?: TAG, message ?: "") }
        override fun logDebug(tag: String?, message: String?) { Log.d(tag ?: TAG, message ?: "") }
        override fun logVerbose(tag: String?, message: String?) { Log.v(tag ?: TAG, message ?: "") }
        override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {
            Log.e(tag ?: TAG, message ?: "", e)
        }
        override fun logStackTrace(tag: String?, e: Exception?) { Log.e(tag ?: TAG, "", e) }
    }
}
