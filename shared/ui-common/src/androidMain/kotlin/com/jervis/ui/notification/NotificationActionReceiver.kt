package com.jervis.ui.notification

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * BroadcastReceiver for notification action buttons.
 *
 * When user taps "Povolit" or "Zamítnout" on an approval notification,
 * this receiver posts the action to [NotificationActionChannel]
 * and dismisses the notification.
 */
class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val taskId = intent.getStringExtra("taskId") ?: return
        val action = when (intent.action) {
            PlatformNotificationManager.ACTION_APPROVE -> NotificationAction.APPROVE
            PlatformNotificationManager.ACTION_DENY -> NotificationAction.DENY
            else -> return
        }

        // Post to shared channel — MainViewModel collects and dispatches
        CoroutineScope(Dispatchers.IO).launch {
            NotificationActionChannel.actions.emit(
                NotificationActionResult(taskId = taskId, action = action),
            )
        }

        // Dismiss the notification
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(taskId.hashCode())
    }
}
