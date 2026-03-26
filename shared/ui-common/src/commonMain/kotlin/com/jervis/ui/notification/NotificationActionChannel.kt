package com.jervis.ui.notification

import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * Shared callback channel for notification action results.
 *
 * Platform-specific notification handlers (BroadcastReceiver on Android,
 * UNNotificationCenterDelegate on iOS) emit results here.
 * MainViewModel collects from this flow and dispatches actions.
 */
object NotificationActionChannel {
    val actions = MutableSharedFlow<NotificationActionResult>(extraBufferCapacity = 10)
}

/**
 * Result of a user action on a notification.
 */
data class NotificationActionResult(
    val taskId: String,
    val action: NotificationAction,
    /** Text reply from inline notification input (e.g. MFA code). */
    val replyText: String? = null,
)

/**
 * Actions available from push/local notifications.
 */
enum class NotificationAction {
    /** User approved the action (commit, push, etc.) */
    APPROVE,

    /** User denied the action — will prompt for reason */
    DENY,

    /** User tapped notification to open the app */
    OPEN,

    /** User replied inline (e.g. MFA code from notification) */
    REPLY,
}
