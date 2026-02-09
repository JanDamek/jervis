package com.jervis.mobile

import com.jervis.ui.notification.NotificationAction
import com.jervis.ui.notification.NotificationActionChannel
import com.jervis.ui.notification.NotificationActionResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Bridge between Swift UNNotificationCenterDelegate and KMP notification system.
 *
 * Called from NotificationDelegate.swift when user interacts with
 * a notification's action buttons (Approve/Deny/Open).
 *
 * Forwards the action to [NotificationActionChannel] which is
 * collected by MainViewModel.
 */
object NotificationBridge {
    fun handleAction(taskId: String?, action: String) {
        val notifAction = when (action) {
            "APPROVE" -> NotificationAction.APPROVE
            "DENY" -> NotificationAction.DENY
            "OPEN" -> NotificationAction.OPEN
            else -> return
        }

        CoroutineScope(Dispatchers.Main).launch {
            NotificationActionChannel.actions.emit(
                NotificationActionResult(
                    taskId = taskId ?: "",
                    action = notifAction,
                ),
            )
        }
    }
}
