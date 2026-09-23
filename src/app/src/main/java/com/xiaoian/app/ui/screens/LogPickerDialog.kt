package com.xiaoian.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.xiaoian.app.logs.DesktopLogs
import com.xiaoian.app.logs.LogFile
import com.xiaoian.app.model.Desktop
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private class Listing(val files: List<LogFile>?)

/** The logs of one desktop, newest first: date and name per row. */
@Composable
fun LogPickerDialog(de: Desktop, onDismiss: () -> Unit, onPick: (LogFile) -> Unit) {
    // Null while loading; Listing(null) when the root shell failed.
    val listing by produceState<Listing?>(null, de) { value = Listing(DesktopLogs.list(de)) }
    val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${de.displayName} logs") },
        text = {
            val files = listing?.files
            when {
                listing == null -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                files == null -> Text("Could not list the logs: no root shell.", color = MaterialTheme.colorScheme.error)
                files.isEmpty() -> Text("No logs yet. They are written when the desktop starts.")
                else -> LazyColumn(modifier = Modifier.heightIn(max = 400.dp)) {
                    items(files) { file ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPick(file) }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                dateFormat.format(Date(file.modified)),
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(file.name, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
    )
}
