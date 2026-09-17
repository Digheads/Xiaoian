package com.xiaoian.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun LogViewerScreen() {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Session Logs", style = MaterialTheme.typography.headlineMedium)
    }
}
