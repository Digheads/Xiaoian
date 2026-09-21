package com.xiaoian.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import com.xiaoian.app.service.XiaoianService
import com.xiaoian.app.ui.theme.XiaoianTheme

/**
 * Asks before the virtual lock. A dialog of its own, not one on the
 * dashboard, because the notification's Lock action has to show it too, and
 * a notification can only start an Activity.
 */
class LockConfirmActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            XiaoianTheme {
                AlertDialog(
                    onDismissRequest = { finish() },
                    title = { Text("Lock the phone?") },
                    text = {
                        Text(
                            "The phone's screen turns off and stops responding to touch. " +
                                "The desktop keeps running on the external display.\n\n" +
                                "To unlock, press Volume Down twice, quickly. The power button " +
                                "works too, but the screen goes to sleep once first: wake it as " +
                                "usual and touch is back."
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            startService(Intent(this, XiaoianService::class.java).apply {
                                action = XiaoianService.ACTION_LOCK
                            })
                            finish()
                        }) { Text("Lock") }
                    },
                    dismissButton = { TextButton(onClick = { finish() }) { Text("Cancel") } },
                )
            }
        }
    }
}
