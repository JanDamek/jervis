package com.jervis.ui.notification

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

object NotificationBridge {
    fun handleAction(taskId: String?, action: String, replyText: String?) {
        val notifAction = when (action) {
            "APPROVE" -> NotificationAction.APPROVE
            "DENY" -> NotificationAction.DENY
            "REPLY" -> NotificationAction.REPLY
            "OPEN" -> NotificationAction.OPEN
            else -> return
        }

        CoroutineScope(Dispatchers.Main).launch {
            NotificationActionChannel.actions.emit(
                NotificationActionResult(
                    taskId = taskId ?: "",
                    action = notifAction,
                    replyText = replyText,
                ),
            )
        }
    }
}
