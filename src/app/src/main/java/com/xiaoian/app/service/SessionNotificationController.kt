package com.xiaoian.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.xiaoian.app.MainActivity
import com.xiaoian.app.R
import com.xiaoian.app.model.Desktop
import com.xiaoian.app.model.DisplayMode

/**
 * Builds and posts the ongoing session notification.
 *
 * Extracted from [XiaoianService] so the service only has to say *what* to
 * show, not how to assemble the three actions (Stop / Lock or Unlock /
 * Preferences). It owns the channel, the ids and the PendingIntents; the
 * service just calls [show] with the current session state.
 */
class SessionNotificationController(private val context: Context) {

    fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                XiaoianService.CHANNEL_ID,
                context.getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = context.getString(R.string.notification_channel_desc)
            }
            context.getSystemService(Context.NOTIFICATION_SERVICE)
                .let { it as NotificationManager }
                .createNotificationChannel(channel)
        }
    }

    fun show(de: Desktop, mode: DisplayMode, state: SessionState, text: String, setup: SetupProgress?) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(XiaoianService.NOTIFICATION_ID, build(de, mode, state, text, setup))
    }

    /** Builds the notification; [show] is the convenience that posts it. */
    fun build(
        de: Desktop,
        mode: DisplayMode,
        state: SessionState,
        text: String,
        setup: SetupProgress?,
    ): Notification {
        val builder = NotificationCompat.Builder(context, XiaoianService.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Xiaoian — ${de.id} (${mode.id})")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)

        if (setup != null) {
            val fraction = setup.fraction
            builder.setProgress(100, ((fraction ?: 0f) * 100).toInt(), fraction == null)
        }

        val contentPending = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        builder.setContentIntent(contentPending)

        // The actions belong to a running session. While it is starting there
        // is nothing to open preferences for yet, and Stop would run the
        // script's teardown under the start script, which is still installing
        // and mounting in the same chroot -- the dashboard offers no Stop
        // there either. While stopping they would do nothing.
        val running = state as? SessionState.Running ?: return builder.build()

        val stopPending = PendingIntent.getService(
            context, 1,
            Intent(context, XiaoianService::class.java).apply { action = XiaoianService.ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE,
        )
        builder.addAction(0, "Stop", stopPending)

        if (mode != DisplayMode.LOCAL) {
            val isLocked = running.isLocked
            // Locking asks first (LockConfirmActivity); only that dialog
            // sends ACTION_LOCK.
            val lockPending = if (isLocked) {
                PendingIntent.getService(
                    context, 2,
                    Intent(context, XiaoianService::class.java).apply { action = XiaoianService.ACTION_UNLOCK },
                    PendingIntent.FLAG_IMMUTABLE,
                )
            } else {
                PendingIntent.getActivity(
                    context, 2,
                    Intent(context, com.xiaoian.app.LockConfirmActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_IMMUTABLE,
                )
            }
            builder.addAction(0, if (isLocked) "Unlock" else "Lock", lockPending)
        }

        // Each frontend has its own settings screen. This was hard-coded to
        // the X11 one, so a KDE session opened Termux:X11's preferences.
        val prefsActivity = if (de == Desktop.KDE)
            "com.anland.termux.SettingsActivity"
        else
            "com.termux.x11.LoriePreferences"
        val prefsIntent = Intent().apply {
            setClassName(context, prefsActivity)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val prefsPending = PendingIntent.getActivity(context, 3, prefsIntent, PendingIntent.FLAG_IMMUTABLE)
        builder.addAction(0, "Preferences", prefsPending)

        return builder.build()
    }
}
