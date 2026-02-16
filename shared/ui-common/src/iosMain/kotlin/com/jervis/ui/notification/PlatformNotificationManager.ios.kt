package com.jervis.ui.notification

import platform.Foundation.NSUUID
import platform.UserNotifications.UNAuthorizationOptionAlert
import platform.UserNotifications.UNAuthorizationOptionBadge
import platform.UserNotifications.UNAuthorizationOptionSound
import platform.UserNotifications.UNMutableNotificationContent
import platform.UserNotifications.UNNotificationAction
import platform.UserNotifications.UNNotificationActionOptionForeground
import platform.UserNotifications.UNNotificationCategory
import platform.UserNotifications.UNNotificationCategoryOptionNone
import platform.UserNotifications.UNNotificationRequest
import platform.UserNotifications.UNNotificationSound
import platform.UserNotifications.UNTimeIntervalNotificationTrigger
import platform.UserNotifications.UNUserNotificationCenter

/**
 * iOS notification manager using UNUserNotificationCenter.
 *
 * Registers a notification category "TASK_APPROVAL" with
 * Approve and Deny action buttons for orchestrator interrupts.
 *
 * The Swift NotificationDelegate handles action callbacks
 * and forwards them via NotificationBridge → NotificationActionChannel.
 */
actual class PlatformNotificationManager actual constructor() {
    companion object {
        const val CATEGORY_APPROVAL = "TASK_APPROVAL"
        const val ACTION_APPROVE = "APPROVE"
        const val ACTION_DENY = "DENY"
    }

    private var _hasPermission = false

    actual fun initialize() {
        val center = UNUserNotificationCenter.currentNotificationCenter()

        // Register approval category with action buttons
        val approveAction = UNNotificationAction.actionWithIdentifier(
            identifier = ACTION_APPROVE,
            title = "Povolit",
            options = UNNotificationActionOptionForeground,
        )
        val denyAction = UNNotificationAction.actionWithIdentifier(
            identifier = ACTION_DENY,
            title = "Zamítnout",
            options = UNNotificationActionOptionForeground,
        )

        val approvalCategory = UNNotificationCategory.categoryWithIdentifier(
            identifier = CATEGORY_APPROVAL,
            actions = listOf(approveAction, denyAction),
            intentIdentifiers = emptyList<String>(),
            options = UNNotificationCategoryOptionNone,
        )

        center.setNotificationCategories(setOf(approvalCategory))
    }

    actual fun requestPermission() {
        val center = UNUserNotificationCenter.currentNotificationCenter()
        center.requestAuthorizationWithOptions(
            options = UNAuthorizationOptionAlert or UNAuthorizationOptionSound or UNAuthorizationOptionBadge,
        ) { granted, error ->
            _hasPermission = granted
            if (error != null) {
                println("iOS notification permission error: ${error.localizedDescription}")
            }
        }
    }

    actual val hasPermission: Boolean
        get() = _hasPermission

    actual fun showNotification(
        title: String,
        body: String,
        taskId: String?,
        isApproval: Boolean,
        interruptAction: String?,
        badgeCount: Int?,
    ) {
        val content = UNMutableNotificationContent().apply {
            setTitle(title)
            setBody(body)
            setSound(UNNotificationSound.defaultSound())

            if (isApproval) {
                setCategoryIdentifier(CATEGORY_APPROVAL)
            }

            if (taskId != null) {
                setUserInfo(mapOf("taskId" to taskId))
            }
        }

        // Trigger immediately (1 second delay is minimum for time interval)
        val trigger = UNTimeIntervalNotificationTrigger.triggerWithTimeInterval(
            timeInterval = 1.0,
            repeats = false,
        )

        val requestId = taskId ?: NSUUID().UUIDString()
        val request = UNNotificationRequest.requestWithIdentifier(
            identifier = requestId,
            content = content,
            trigger = trigger,
        )

        UNUserNotificationCenter.currentNotificationCenter()
            .addNotificationRequest(request) { error ->
                if (error != null) {
                    println("Failed to schedule iOS notification: ${error.localizedDescription}")
                }
            }
    }

    actual fun cancelNotification(taskId: String) {
        UNUserNotificationCenter.currentNotificationCenter()
            .removePendingNotificationRequestsWithIdentifiers(listOf(taskId))
        UNUserNotificationCenter.currentNotificationCenter()
            .removeDeliveredNotificationsWithIdentifiers(listOf(taskId))
    }
}
