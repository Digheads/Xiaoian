package com.xiaoian.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xiaoian.app.logs.DesktopLogs

/**
 * Read-only view of one desktop log. Opens scrolled to the end, where the
 * latest -- and usually the interesting -- lines are.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogViewerScreen(path: String, onBack: () -> Unit) {
    var reload by remember { mutableIntStateOf(0) }
    val content by produceState<DesktopLogs.Content?>(null, path, reload) {
        value = null
        value = DesktopLogs.read(path)
    }
    val listState = rememberLazyListState()
    LaunchedEffect(content) {
        val text = content as? DesktopLogs.Content.Text ?: return@LaunchedEffect
        // The "only the last KB" note is an item of its own ahead of the lines.
        if (text.lines.isNotEmpty()) listState.scrollToItem(text.lines.lastIndex + if (text.truncated) 1 else 0)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(path.substringAfterLast('/')) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { reload++ }, enabled = content != null) {
                        Icon(Icons.Default.Refresh, contentDescription = "Reload")
                    }
                },
            )
        }
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            when (val c = content) {
                null -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                is DesktopLogs.Content.Failed -> Text(
                    c.message,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(16.dp),
                )
                is DesktopLogs.Content.Text -> if (c.lines.isEmpty()) {
                    Text(
                        "The log is empty.",
                        modifier = Modifier.align(Alignment.Center),
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                } else {
                    val mono = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                    )
                    SelectionContainer {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                        ) {
                            if (c.truncated) item {
                                Text(
                                    "Only the last ${DesktopLogs.MAX_READ_BYTES / 1024} KB are shown.",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                    modifier = Modifier.padding(bottom = 4.dp),
                                )
                            }
                            items(c.lines) { line -> Text(line, style = mono) }
                        }
                    }
                }
            }
        }
    }
}
