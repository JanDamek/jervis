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
import platform.UserNotifications.UNTextInputNotificationAction
import platform.UserNotifications.UNTimeIntervalNotificationTrigger
import platform.UserNotifications.UNUserNotificationCenter

/**
 * iOS notification manager using UNUserNotificationCenter.
 *
 * Registers notification categories:
 * - TASK_APPROVAL: Approve/Deny buttons for orchestrator interrupts
 * - MFA_CODE: Text input action for MFA code entry (authenticator_code, sms_code)
 * - MFA_CONFIRM: Simple confirm button for authenticator_number/phone_call
 *
 * The Swift NotificationDelegate handles action callbacks
 * and forwards them via NotificationBridge → NotificationActionChannel.
 */
actual class PlatformNotificationManager actual constructor() {
    companion object {
        const val CATEGORY_APPROVAL = "APPROVAL"
        const val CATEGORY_MFA_CODE = "MFA_CODE"
        const val CATEGORY_MFA_CONFIRM = "MFA_CONFIRM"
        const val ACTION_APPROVE = "APPROVE"
        const val ACTION_DENY = "DENY"
        const val ACTION_MFA_REPLY = "MFA_REPLY"
        const val ACTION_MFA_CONFIRM = "MFA_CONFIRM"
    }

    private var _hasPermission = false

    actual fun initialize() {
        val center = UNUserNotificationCenter.currentNotificationCenter()

        // Approval category with Approve/Deny buttons
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

        // MFA code input category — text input for authenticator_code / sms_code
        val mfaReplyAction = UNTextInputNotificationAction.actionWithIdentifier(
            identifier = ACTION_MFA_REPLY,
            title = "Zadat kód",
            options = UNNotificationActionOptionForeground,
            textInputButtonTitle = "Odeslat",
            textInputPlaceholder = "Kód",
        )
        val mfaCodeCategory = UNNotificationCategory.categoryWithIdentifier(
            identifier = CATEGORY_MFA_CODE,
            actions = listOf(mfaReplyAction),
            intentIdentifiers = emptyList<String>(),
            options = UNNotificationCategoryOptionNone,
        )

        // MFA confirm category — simple button for authenticator_number / phone_call
        val mfaConfirmAction = UNNotificationAction.actionWithIdentifier(
            identifier = ACTION_MFA_CONFIRM,
            title = "Potvrzeno",
            options = UNNotificationActionOptionForeground,
        )
        val mfaConfirmCategory = UNNotificationCategory.categoryWithIdentifier(
            identifier = CATEGORY_MFA_CONFIRM,
            actions = listOf(mfaConfirmAction),
            intentIdentifiers = emptyList<String>(),
            options = UNNotificationCategoryOptionNone,
        )

        center.setNotificationCategories(setOf(approvalCategory, mfaCodeCategory, mfaConfirmCategory))
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
        mfaType: String?,
        mfaNumber: String?,
    ) {
        val content = UNMutableNotificationContent().apply {
            setTitle(title)
            setBody(body)
            setSound(UNNotificationSound.defaultSound())

            // Set category for actionable notifications
            val category = when {
                interruptAction == "o365_mfa" && mfaType in listOf("authenticator_code", "sms_code") ->
                    CATEGORY_MFA_CODE
                interruptAction == "o365_mfa" ->
                    CATEGORY_MFA_CONFIRM
                isApproval ->
                    CATEGORY_APPROVAL
                else -> null
            }
            category?.let { setCategoryIdentifier(it) }

            // Set interruption level for urgent MFA notifications
            if (interruptAction in listOf("o365_mfa", "o365_relogin")) {
                setInterruptionLevel(platform.UserNotifications.UNNotificationInterruptionLevel.UNNotificationInterruptionLevelTimeSensitive)
            }

            val info = mutableMapOf<Any?, Any?>("taskId" to (taskId ?: ""))
            mfaType?.let { info["mfaType"] = it }
            mfaNumber?.let { info["mfaNumber"] = it }
            setUserInfo(info)
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
