package com.jervis.ui.notification

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * BroadcastReceiver for notification action buttons.
 *
 * Handles:
 * - "Povolit" / "Zamítnout" on approval notifications
 * - Inline MFA code reply via RemoteInput
 *
 * Posts to [NotificationActionChannel] for NotificationViewModel to dispatch.
 */
class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val taskId = intent.getStringExtra("taskId") ?: return

        // Check for inline MFA code reply (RemoteInput)
        if (intent.action == PlatformNotificationManager.ACTION_REPLY) {
            val remoteInputBundle = RemoteInput.getResultsFromIntent(intent)
            val mfaCode = remoteInputBundle
                ?.getCharSequence(PlatformNotificationManager.KEY_MFA_CODE)
                ?.toString()
                ?.trim()

            if (!mfaCode.isNullOrBlank()) {
                CoroutineScope(Dispatchers.IO).launch {
                    NotificationActionChannel.actions.emit(
                        NotificationActionResult(
                            taskId = taskId,
                            action = NotificationAction.REPLY,
                            replyText = mfaCode,
                        ),
                    )
                }
                // Dismiss the notification
                val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                manager.cancel(taskId.hashCode())
            }
            return
        }

        val action = when (intent.action) {
            PlatformNotificationManager.ACTION_APPROVE -> NotificationAction.APPROVE
            PlatformNotificationManager.ACTION_DENY -> NotificationAction.DENY
            else -> return
        }

        // Post to shared channel — NotificationViewModel collects and dispatches
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
