package com.xiaoian.app.terminal

import android.content.Context
import android.util.Log
import com.termux.terminal.XiaoianPty
import com.xiaoian.app.shell.RootShell
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * A pty with a root process on the far end of it.
 *
 * The app opens the pty with its own uid -- an app uid can open `/dev/ptmx`
 * under Enforcing SELinux, measured on this device -- and the already-open
 * [RootShell] runs `libptyspawn.so` on the slave. That way a terminal session
 * costs no `su` invocation at all, so no Magisk prompt and no toast.
 *
 * [sid] is both the pid of that process and, because `libptyspawn.so` calls
 * `setsid()`, its session id. Teardown selects on the session id, which is why
 * no marker files are needed and why it is structurally incapable of touching
 * a desktop's own processes.
 */
class RootPty private constructor(
    val masterFd: Int,
    val ptsPath: String,
    val sid: Int,
    private val scriptFile: File,
) {

    companion object {
        private const val TAG = "RootPty"

        /**
         * Size the pty starts at. The real size arrives an instant later, from
         * the view's first layout, as a plain ioctl on the master.
         */
        private const val INITIAL_ROWS = 24
        private const val INITIAL_COLS = 80

        /** How long the shell gets between TERM and KILL. */
        private const val TERM_GRACE_MS = 1_500L

        private val counter = AtomicInteger()

        /**
         * Creates the pty and starts [spec]'s script on it. Blocking: the root
         * shell is involved, so never call this from the main thread.
         */
        fun start(context: Context, spec: SessionSpec): Result<RootPty> {
            val fdOut = IntArray(1)
            val pts = try {
                XiaoianPty.openPty(fdOut, INITIAL_ROWS, INITIAL_COLS, 0, 0)
            } catch (e: Throwable) {
                return Result.failure(e)
            }

            // The script goes through a file rather than the command line:
            // it is a couple of dozen lines with quoting of its own, and
            // nesting that inside the root shell's stdin is how quoting bugs
            // get in. filesDir is app-private but readable by root.
            val dir = File(context.filesDir, "terminal").apply { mkdirs() }
            val script = File(dir, "session-${counter.incrementAndGet()}.sh")
            script.writeText(SessionScripts.of(context, spec))

            val spawn = File(context.applicationInfo.nativeLibraryDir, "libptyspawn.so").absolutePath
            val pidLines = mutableListOf<String>()
            // Backgrounded, so the root shell is free again immediately and
            // the session outlives the command that started it. $! is the
            // helper's pid, which setsid() also makes its session id.
            val result = RootShell.shared.execBlocking(
                "'$spawn' '$pts' /system/bin/sh '${script.absolutePath}' &\necho ${'$'}!",
                idleTimeoutMs = 20_000L,
            ) { line -> pidLines += line }

            val sid = pidLines.asReversed().firstNotNullOfOrNull { it.trim().toIntOrNull() }
            if (!result.success || sid == null || sid <= 0) {
                XiaoianPty.close(fdOut[0])
                script.delete()
                val why = result.failure
                    ?: result.stderr.takeIf { it.isNotBlank() }
                    ?: "libptyspawn.so did not report a pid"
                Log.e(TAG, "could not start a session on $pts: $why")
                return Result.failure(IllegalStateException(why))
            }

            Log.i(TAG, "session ${spec.group} on $pts, sid $sid")
            return Result.success(RootPty(fdOut[0], pts, sid, script))
        }

        /**
         * Ends every process in session [sid], gently first.
         *
         * Not `kill -- -pid`: that reaches one process group, and an
         * interactive bash puts each of its jobs in a group of its own. The
         * session is the enclosing thing, and selecting on it cannot reach
         * anything the terminal did not start.
         *
         * Not [com.termux.terminal.TerminalSession.finishIfRunning] either, on
         * its own: under Magisk `su` is a client and the real process is
         * forked by the daemon, so a SIGKILL from this uid does not land. That
         * is the failure mode that used to leave orphaned chroot processes
         * behind and freeze the phone.
         */
        fun killSession(sid: Int) {
            RootShell.shared.execBlocking(killScript(sid, "TERM"), idleTimeoutMs = 20_000L)
            Thread.sleep(TERM_GRACE_MS)
            RootShell.shared.execBlocking(killScript(sid, "KILL"), idleTimeoutMs = 20_000L)
        }

        // Field 4 after the comm is the session id (proc(5)). comm is
        // parenthesised and may itself contain "') '", hence the longest-match
        // strip. Kept to shell builtins -- no awk, no pkill -- so it does not
        // depend on what this ROM's toybox happens to include.
        private fun killScript(sid: Int, signal: String): String = """
            (
            for d in /proc/[0-9]*; do
                read -r line < "${'$'}d/stat" 2>/dev/null || continue
                rest=${'$'}{line##*\) }
                set -- ${'$'}rest
                [ "${'$'}4" = "$sid" ] && kill -$signal "${'$'}{d#/proc/}" 2>/dev/null
            done
            ) 2>/dev/null; :
        """.trimIndent()
    }

    /**
     * Drops what the session leaves behind. Safe to call twice.
     *
     * [closeMaster] must be false once a TerminalSession has adopted the fd:
     * it closes it itself in `cleanupResources()`, and closing a descriptor
     * twice can take an unrelated one down with it.
     */
    fun cleanup(closeMaster: Boolean) {
        if (closeMaster) runCatching { XiaoianPty.close(masterFd) }
        runCatching { scriptFile.delete() }
    }
}
