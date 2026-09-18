package com.xiaoian.app.service

import com.xiaoian.app.shell.ScriptMessage

enum class StepStatus { PENDING, RUNNING, DONE }

data class SetupStep(val id: String, val title: String, val status: StepStatus)

/** What the dashboard and the notification show while a session starts. */
data class SetupProgress(
    val steps: List<SetupStep> = emptyList(),
    /** 0..1, or null when the running step has no measurable progress. */
    val fraction: Float? = null,
    val bytesDone: Long = 0,
    /** Negative when the running step reports no byte counts or no total. */
    val bytesTotal: Long = -1,
    val bytesPerSecond: Long = 0,
    /** The latest human-readable line from the script or apt. */
    val detail: String = "",
) {
    val current: SetupStep? get() = steps.firstOrNull { it.status == StepStatus.RUNNING }
}

/**
 * Folds parsed script output into a [SetupProgress]. Pure state machine:
 * the caller passes the clock, so it stays independent of Android.
 */
class SetupProgressTracker {

    var progress = SetupProgress()
        private set

    private var speedSampleTime = 0L
    private var speedSampleBytes = 0L

    fun reset() {
        progress = SetupProgress()
    }

    /** Returns true when [progress] changed. */
    fun onMessage(msg: ScriptMessage, nowMs: Long): Boolean {
        val before = progress
        progress = when (msg) {
            is ScriptMessage.StepPlanned ->
                if (progress.steps.any { it.id == msg.id }) progress
                else progress.copy(steps = progress.steps + SetupStep(msg.id, msg.title, StepStatus.PENDING))

            is ScriptMessage.StepStarted -> {
                val steps = if (progress.steps.any { it.id == msg.id }) progress.steps
                            else progress.steps + SetupStep(msg.id, msg.id, StepStatus.PENDING)
                progress.copy(
                    steps = steps.map { if (it.id == msg.id) it.copy(status = StepStatus.RUNNING) else it },
                    fraction = null, bytesDone = 0, bytesTotal = -1, bytesPerSecond = 0,
                )
            }

            is ScriptMessage.StepDone -> progress.copy(
                steps = progress.steps.map { if (it.id == msg.id) it.copy(status = StepStatus.DONE) else it },
                fraction = null, bytesDone = 0, bytesTotal = -1, bytesPerSecond = 0,
            )

            is ScriptMessage.BytesProgress -> {
                if (msg.done == 0L || msg.done < progress.bytesDone) {
                    speedSampleTime = nowMs
                    speedSampleBytes = msg.done
                }
                progress.copy(
                    fraction = if (msg.total > 0) (msg.done.toFloat() / msg.total).coerceIn(0f, 1f) else null,
                    bytesDone = msg.done,
                    bytesTotal = msg.total,
                    bytesPerSecond = speed(msg.done, nowMs),
                )
            }

            is ScriptMessage.AptProgress -> progress.copy(
                fraction = (msg.percent / 100f).coerceIn(0f, 1f),
                bytesTotal = -1,
                detail = msg.text.ifEmpty { progress.detail },
            )

            is ScriptMessage.Info -> if (msg.text.isBlank()) progress else progress.copy(detail = msg.text)
            is ScriptMessage.Warning -> progress.copy(detail = msg.text)
            is ScriptMessage.Error -> progress.copy(detail = msg.text)
        }
        return progress != before
    }

    /** Average over a window of at least 1 s, re-started every 3 s to follow changes. */
    private fun speed(done: Long, nowMs: Long): Long {
        val elapsed = nowMs - speedSampleTime
        if (elapsed < 1000) return progress.bytesPerSecond
        val bps = (done - speedSampleBytes) * 1000 / elapsed
        if (elapsed >= 3000) {
            speedSampleTime = nowMs
            speedSampleBytes = done
        }
        return bps
    }
}
