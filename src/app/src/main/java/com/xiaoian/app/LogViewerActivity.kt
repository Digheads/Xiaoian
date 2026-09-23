package com.xiaoian.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.xiaoian.app.ui.screens.LogViewerScreen
import com.xiaoian.app.ui.theme.XiaoianTheme

/** A desktop log, read-only. Opened from the log picker on an environment card. */
class LogViewerActivity : ComponentActivity() {

    companion object {
        private const val EXTRA_PATH = "path"

        fun intent(context: Context, path: String): Intent =
            Intent(context, LogViewerActivity::class.java).putExtra(EXTRA_PATH, path)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val path = intent.getStringExtra(EXTRA_PATH) ?: return finish()
        enableEdgeToEdge()
        setContent {
            XiaoianTheme {
                LogViewerScreen(path = path, onBack = { finish() })
            }
        }
    }
}
