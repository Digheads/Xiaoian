package com.xiaoian.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import com.xiaoian.app.ui.screens.SettingsScreen
import com.xiaoian.app.ui.theme.XiaoianTheme

/**
 * The app's own settings.
 *
 * An Activity of its own rather than a screen inside [MainActivity], so the
 * gear on the terminal's key bar does not drag the user out of the terminal's
 * task -- Back comes straight back to the shell. It is also how both vendored
 * frontends do it, so the three screens behave alike.
 *
 * What it does *not* do is replace those two: see [SettingsScreen].
 */
class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            XiaoianTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    SettingsScreen(innerPadding = innerPadding)
                }
            }
        }
    }
}
