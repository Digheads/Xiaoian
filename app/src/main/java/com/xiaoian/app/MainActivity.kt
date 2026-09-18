package com.xiaoian.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import androidx.compose.runtime.*
import com.xiaoian.app.ui.screens.DashboardScreen
import com.xiaoian.app.ui.screens.TerminalScreen
import com.xiaoian.app.ui.theme.XiaoianTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            var currentScreen by remember { mutableStateOf("dashboard") }

            XiaoianTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    if (currentScreen == "dashboard") {
                        DashboardScreen(innerPadding = innerPadding, onOpenTerminal = { currentScreen = "terminal" })
                    } else {
                        TerminalScreen(onBack = { currentScreen = "dashboard" })
                    }
                }
            }
        }
    }
}
