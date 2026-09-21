package com.xiaoian.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xiaoian.app.service.SetupProgress
import com.xiaoian.app.service.SetupStep
import com.xiaoian.app.service.StepStatus
import java.util.Locale

/** Step checklist, progress bar and latest log line while a session starts. */
@Composable
fun SetupProgressView(progress: SetupProgress, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth()) {
        // The live line from the script, not the step name: the checklist below
        // already says which step is running, and repeating it here left the
        // only moving text -- what apt is actually doing -- at the very bottom
        // of the card.
        Text(
            progress.detail.trim().replace(Regex("\\s+"), " ")
                .ifBlank { progress.current?.title ?: "Starting session..." },
            style = MaterialTheme.typography.bodyMedium,
            // One line, cut with an ellipsis: a package name long enough to
            // wrap used to push everything below down and back up.
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(modifier = Modifier.height(12.dp))

        val fraction = progress.fraction
        if (fraction != null) {
            LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        Spacer(modifier = Modifier.height(4.dp))
        // Kept even when empty, for the same reason.
        Text(
            progressLabel(progress) ?: "",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        Spacer(modifier = Modifier.height(20.dp))
        progress.steps.forEach { StepRow(it) }
    }
}

@Composable
private fun StepRow(step: SetupStep) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start
    ) {
        when (step.status) {
            StepStatus.DONE -> Icon(
                Icons.Filled.CheckCircle, contentDescription = null,
                tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp)
            )
            StepStatus.RUNNING -> CircularProgressIndicator(
                strokeWidth = 2.dp, modifier = Modifier.size(20.dp)
            )
            StepStatus.PENDING -> Icon(
                Icons.Outlined.RadioButtonUnchecked, contentDescription = null,
                tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(20.dp)
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            step.title,
            style = MaterialTheme.typography.bodyLarge,
            color = if (step.status == StepStatus.PENDING) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.onSurface
        )
    }
}

/** "123.4 / 210.0 MB · 4.2 MB/s", or "52%" for apt progress. */
private fun progressLabel(p: SetupProgress): String? {
    if (p.bytesDone > 0) {
        val parts = mutableListOf(
            if (p.bytesTotal > 0) "${mb(p.bytesDone)} / ${mb(p.bytesTotal)} MB" else "${mb(p.bytesDone)} MB"
        )
        if (p.bytesPerSecond > 0) parts += "${mb(p.bytesPerSecond)} MB/s"
        return parts.joinToString(" · ")
    }
    return p.fraction?.let { "${(it * 100).toInt()}%" }
}

private fun mb(bytes: Long) = String.format(Locale.US, "%.1f", bytes / 1_048_576.0)
