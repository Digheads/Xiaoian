package com.xiaoian.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xiaoian.app.service.SessionState
import com.xiaoian.app.ui.components.AppLogo

@Composable
fun DashboardScreen(innerPadding: PaddingValues = PaddingValues(0.dp), viewModel: DashboardViewModel = viewModel()) {
    val state by viewModel.sessionState.collectAsState()
    val setup by viewModel.setupProgress.collectAsState()
    
    var selectedDE by remember { mutableStateOf("kde") }
    var selectedMode by remember { mutableStateOf("extend") }

    Column(
        modifier = Modifier.fillMaxSize().padding(innerPadding).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppLogo(modifier = Modifier.size(48.dp))
            Spacer(modifier = Modifier.width(12.dp))
            Text("Xiaoian", style = MaterialTheme.typography.headlineLarge)
        }
        Spacer(modifier = Modifier.height(32.dp))
        
        when (val currentState = state) {
            is SessionState.Idle -> {
                // DE Selection
                Text("Desktop Environment", style = MaterialTheme.typography.titleMedium)
                Row(modifier = Modifier.padding(vertical = 8.dp)) {
                    RadioButton(selected = selectedDE == "kde", onClick = { selectedDE = "kde" })
                    Text("KDE (Wayland)", modifier = Modifier.align(Alignment.CenterVertically))
                    Spacer(modifier = Modifier.width(16.dp))
                    RadioButton(selected = selectedDE == "xfce", onClick = { selectedDE = "xfce" })
                    Text("XFCE (X11)", modifier = Modifier.align(Alignment.CenterVertically))
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                
                // Mode Selection
                Text("Display Mode", style = MaterialTheme.typography.titleMedium)
                Row(modifier = Modifier.padding(vertical = 8.dp)) {
                    RadioButton(selected = selectedMode == "extend", onClick = { selectedMode = "extend" })
                    Text("Extend", modifier = Modifier.align(Alignment.CenterVertically))
                    Spacer(modifier = Modifier.width(8.dp))
                    RadioButton(selected = selectedMode == "mirror", onClick = { selectedMode = "mirror" })
                    Text("Mirror", modifier = Modifier.align(Alignment.CenterVertically))
                    Spacer(modifier = Modifier.width(8.dp))
                    RadioButton(selected = selectedMode == "local", onClick = { selectedMode = "local" })
                    Text("Local", modifier = Modifier.align(Alignment.CenterVertically))
                }
                
                Spacer(modifier = Modifier.height(32.dp))
                
                Button(
                    onClick = { viewModel.startSession(selectedMode, selectedDE) },
                    modifier = Modifier.fillMaxWidth().height(56.dp)
                ) {
                    Text("START DESKTOP")
                }
            }
            
            is SessionState.Starting -> {
                SetupProgressView(setup)
            }
            
            is SessionState.Running -> {
                Text("RUNNING", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleLarge)
                Text("Mode: ${currentState.mode} | DE: ${currentState.de}")
                Text(if (currentState.isLocked) "Phone is LOCKED" else "Phone is UNLOCKED")
                
                Spacer(modifier = Modifier.height(32.dp))
                
                val context = androidx.compose.ui.platform.LocalContext.current
                Button(
                    onClick = {
                        val intent = android.content.Intent(context, com.termux.x11.MainActivity::class.java)
                        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(intent)
                    },
                    modifier = Modifier.fillMaxWidth().height(56.dp)
                ) {
                    Text("OPEN DESKTOP")
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                
                Button(
                    onClick = { viewModel.stopSession() },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.fillMaxWidth().height(56.dp)
                ) {
                    Text("STOP SESSION")
                }
            }
            
            is SessionState.Stopping -> {
                CircularProgressIndicator()
                Spacer(modifier = Modifier.height(16.dp))
                Text("Stopping session...", style = MaterialTheme.typography.titleLarge)
            }
            
            is SessionState.Error -> {
                Text("ERROR", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.titleLarge)
                Text(currentState.message, color = MaterialTheme.colorScheme.error)
                
                Spacer(modifier = Modifier.height(32.dp))
                
                Button(onClick = { viewModel.stopSession() }) {
                    Text("RESET")
                }
            }
        }
    }
}
