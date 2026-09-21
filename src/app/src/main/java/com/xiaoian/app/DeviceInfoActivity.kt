package com.xiaoian.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import com.xiaoian.app.ui.screens.DeviceInfoScreen
import com.xiaoian.app.ui.theme.XiaoianTheme

/** The dashboard's info button: device support, display settings, supported devices. */
class DeviceInfoActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            XiaoianTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    DeviceInfoScreen(innerPadding = innerPadding)
                }
            }
        }
    }
}
