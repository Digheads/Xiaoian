package com.xiaoian.app.shell

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.Writer
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Outcome of one command run inside a [RootShell]. */
data class RootShellResult(
    val exitCode: Int,
    /** Tail of what the command wrote to stderr, newest last. */
    val stderr: String,
    /** Non-null when the shell itself failed rather than the command. */
    val failure: String? = null,
) {
    val success: Boolean get() = failure == null && exitCode == 0
}

/**
 * One `su` process, held open, fed commands over its stdin.
 *
 * Every `su` invocation costs a Magisk permission prompt -- or, once granted
 * forever, a toast that flashes at the bottom of the screen. Spawning a fresh
 * `su -c` per command meant roughly a dozen of those for a single desktop
 * start. Here `su` is started once, without `-c`, so it reads commands from a
 * pipe for as long as the app lives.
 *
 * Commands are framed: after each one the shell is asked to print a marker
 * carrying the exit status on stdout, and a matching marker on stderr, so the
 * two streams can be told apart and the reader knows where one command's
 * output ends. The marker carries a per-command token, so output left behind
 * by an earlier command can never be read as this one's.
 *
 * Two reader threads drain stdout and stderr for the life of the shell. They
 * have to run continuously: a command that writes more than a pipe buffer to
 * the stream nobody is reading would block forever.
 */
class RootShell(private val label: String) {

    companion object {
        private const val TAG = "RootShell"

        /** How much of a command's stderr is kept. */
        private const val STDERR_TAIL_LINES = 40

        /**
         * No output for this long means the command is wedged. The shell is
         * then killed and reopened, because there is no way to cancel a
         * command that is already running inside it.
         */
        const val DEFAULT_IDLE_TIMEOUT_MS = 120_000L

        /** Long-running scripts stream for minutes; they opt out of the timeout. */
        const val NO_TIMEOUT = 0L

        /**
         * After this many shells in a row die before answering, stop opening
         * new ones for [DENIED_COOLDOWN_MS]. Each attempt is a Magisk prompt,
         * and a denied or ignored prompt would otherwise be followed straight
         * away by the next one, for every command the app wanted to run.
         */
        private const val DENIED_ATTEMPTS = 2
        private const val DENIED_COOLDOWN_MS = 20_000L

        /**
         * The shell for short commands. Serialised, so it must never be given
         * something that runs for minutes -- use [dedicated] for those.
         */
        val shared: RootShell by lazy { RootShell("shared") }

        /**
         * A shell of its own for one long-running script, so it does not hold
         * the [shared] lock while the UI wants to run a quick query.
         * The caller closes it when the script is done.
         */
        fun dedicated(label: String): RootShell = RootShell(label)
    }

    /** One line of output, or the end of the stream. */
    private sealed interface Chunk {
        @JvmInline value class Text(val line: String) : Chunk
        object End : Chunk
    }

    private val lock = ReentrantLock()
    private val commandCounter = AtomicLong()

    private var process: Process? = null
    private var stdin: Writer? = null
    private var stdout = LinkedBlockingQueue<Chunk>()
    private var stderr = LinkedBlockingQueue<Chunk>()

    /** Shells in a row that died before answering -- i.e. root was refused. */
    private var deniedRun = 0
    private var reopenNotBefore = 0L

    /**
     * Runs [command] and returns once it has finished. Lines it writes to
     * stdout are handed to [onOutput] as they arrive.
     *
     * [idleTimeoutMs] is an *idle* timeout, not a total one: a script that
     * keeps producing output may run as long as it likes. Pass [NO_TIMEOUT]
     * for a command that can legitimately go quiet for a long time.
     */
    suspend fun exec(
        command: String,
        idleTimeoutMs: Long = DEFAULT_IDLE_TIMEOUT_MS,
        onOutput: ((String) -> Unit)? = null,
    ): RootShellResult = withContext(Dispatchers.IO) {
        execBlocking(command, idleTimeoutMs, onOutput)
    }

    /**
     * Blocking form of [exec], for the callers that are not suspend functions
     * yet. Same contract, same lock.
     */
    fun execBlocking(
        command: String,
        idleTimeoutMs: Long = DEFAULT_IDLE_TIMEOUT_MS,
        onOutput: ((String) -> Unit)? = null,
    ): RootShellResult = lock.withLock {
        val started = ensureStarted()
        if (started != null) return RootShellResult(-1, "", started)

        val token = "XSH${commandCounter.incrementAndGet()}"
        val writer = stdin ?: return RootShellResult(-1, "", "root shell has no stdin")

        Log.d(TAG, "[$label] $command")
        try {
            // $? has to be captured before anything else runs, or the marker's
            // own printf overwrites it.
            writer.write(command)
            writer.write("\n__xrc=\$?\n")
            writer.write("printf '\\n__OUT_$token %s\\n' \"\$__xrc\"\n")
            writer.write("printf '\\n__ERR_$token\\n' 1>&2\n")
            writer.flush()
        } catch (e: IOException) {
            shutdown()
            return RootShellResult(-1, "", "root shell closed while writing: ${e.message}")
        }

        var exitCode = -1
        val errTail = ArrayDeque<String>()

        // ---- stdout, up to this command's marker -------------------------
        // Each marker is printed after a newline, so that a command whose last
        // line had no trailing newline still gets that line delivered. When the
        // output did end in a newline, that leaves one blank line in front of
        // the marker which the command never wrote. Holding a blank line back
        // until we know what follows drops exactly those, and nothing else.
        var pendingBlank = false
        while (true) {
            val chunk = stdout.poll(idleTimeoutMs)
                ?: return timedOut(command, idleTimeoutMs)
            if (chunk is Chunk.End) {
                // su exiting before it answers means the request was denied or
                // the prompt was never tapped.
                shutdown()
                deniedRun++
                if (deniedRun >= DENIED_ATTEMPTS) {
                    reopenNotBefore = System.currentTimeMillis() + DENIED_COOLDOWN_MS
                }
                return RootShellResult(-1, "", "root was not granted")
            }
            val line = (chunk as Chunk.Text).line
            val marker = line.trim()
            if (marker.startsWith("__OUT_$token")) {
                exitCode = marker.removePrefix("__OUT_$token").trim().toIntOrNull() ?: -1
                // The shell answered, so root is granted and working.
                deniedRun = 0
                reopenNotBefore = 0L
                break
            }
            if (line.isEmpty()) {
                // Might be the marker's own newline; decide when we see what
                // comes next.
                if (pendingBlank) onOutput?.invoke("")
                pendingBlank = true
                continue
            }
            if (pendingBlank) {
                onOutput?.invoke("")
                pendingBlank = false
            }
            onOutput?.invoke(line)
        }

        // ---- stderr, up to its marker ------------------------------------
        // The command has already finished, so this marker is on its way; a
        // short wait is enough even when the caller asked for no timeout.
        val errTimeout = if (idleTimeoutMs == NO_TIMEOUT) 10_000L else idleTimeoutMs
        while (true) {
            val chunk = stderr.poll(errTimeout) ?: break
            if (chunk is Chunk.End) break
            val line = (chunk as Chunk.Text).line
            if (line.trim() == "__ERR_$token") break
            // Same marker newline as on stdout. Nothing diagnostic is ever a
            // blank line, so on this stream they can simply go.
            if (line.isBlank()) continue
            Log.e(TAG, "[$label] stderr: $line")
            errTail.addLast(line)
            if (errTail.size > STDERR_TAIL_LINES) errTail.removeFirst()
        }

        Log.d(TAG, "[$label] exit $exitCode")
        RootShellResult(exitCode, errTail.joinToString("\n").trim())
    }

    /** Closes the shell. A later command reopens it -- and costs a new prompt. */
    fun close() = lock.withLock { shutdown() }

    /** True once a command has run successfully, i.e. root was actually granted. */
    fun isAlive(): Boolean = lock.withLock { process?.isAlive == true }

    // ------------------------------------------------------------------ internals

    private fun LinkedBlockingQueue<Chunk>.poll(timeoutMs: Long): Chunk? =
        if (timeoutMs <= 0) take() else poll(timeoutMs, TimeUnit.MILLISECONDS)

    private fun timedOut(command: String, idleMs: Long): RootShellResult {
        Log.e(TAG, "[$label] no output for ${idleMs}ms, restarting shell. Command: $command")
        // There is no way to cancel a command already running inside the
        // shell, so the shell goes with it.
        shutdown()
        return RootShellResult(-1, "", "command produced no output for ${idleMs / 1000}s")
    }

    /** @return null when the shell is up, otherwise why it is not. */
    private fun ensureStarted(): String? {
        if (process?.isAlive == true) return null
        shutdown()

        val waitLeft = reopenNotBefore - System.currentTimeMillis()
        if (waitLeft > 0) {
            return "root was refused; not asking again for ${waitLeft / 1000 + 1}s"
        }

        return try {
            // No "-c": su execs a shell that reads this pipe for its lifetime.
            val proc = ProcessBuilder("su")
                .redirectErrorStream(false)
                .start()
            process = proc
            stdin = OutputStreamWriter(proc.outputStream)
            stdout = LinkedBlockingQueue()
            stderr = LinkedBlockingQueue()
            pump(BufferedReader(InputStreamReader(proc.inputStream)), stdout, "out")
            pump(BufferedReader(InputStreamReader(proc.errorStream)), stderr, "err")
            Log.i(TAG, "[$label] root shell opened")
            null
        } catch (e: Exception) {
            Log.e(TAG, "[$label] could not open a root shell", e)
            shutdown()
            "could not start su: ${e.message}"
        }
    }

    private fun pump(reader: BufferedReader, queue: LinkedBlockingQueue<Chunk>, kind: String) {
        Thread({
            try {
                reader.forEachLine { queue.put(Chunk.Text(it)) }
            } catch (e: IOException) {
                // The shell was closed under us; End below tells the reader.
            } finally {
                queue.put(Chunk.End)
            }
        }, "RootShell-$label-$kind").apply { isDaemon = true }.start()
    }

    private fun shutdown() {
        runCatching { stdin?.close() }
        runCatching { process?.destroy() }
        stdin = null
        process = null
    }
}
