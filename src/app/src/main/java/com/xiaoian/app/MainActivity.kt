package com.xiaoian.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import androidx.compose.runtime.*
import com.xiaoian.app.terminal.TerminalActivity
import com.xiaoian.app.ui.screens.DashboardScreen
import com.xiaoian.app.ui.theme.XiaoianTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            XiaoianTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    // The terminal is an Activity of its own, not a screen in
                    // this one: it hosts a plain Android View with its own
                    // renderer and input handling, and its sessions outlive
                    // whatever the dashboard is doing.
                    DashboardScreen(
                        innerPadding = innerPadding,
                        onOpenTerminal = { spec -> startActivity(TerminalActivity.intent(this, spec)) },
                        onOpenSettings = { startActivity(Intent(this, SettingsActivity::class.java)) },
                        onOpenDeviceInfo = { startActivity(Intent(this, DeviceInfoActivity::class.java)) },
                    )
                }
            }
        }
    }
}
