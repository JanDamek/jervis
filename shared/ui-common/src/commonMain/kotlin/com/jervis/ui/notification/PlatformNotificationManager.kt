package com.jervis.ui.notification

/**
 * Cross-platform notification manager.
 *
 * expect/actual pattern — each platform provides its own implementation:
 * - Desktop (JVM): macOS osascript / Windows SystemTray / Linux notify-send
 * - Android: NotificationCompat with action buttons for approvals
 * - iOS: UNUserNotificationCenter with UNNotificationAction for approvals
 *
 * Desktop OS notifications don't support action buttons — the in-app
 * ApprovalNotificationDialog handles approve/deny on desktop.
 */
expect class PlatformNotificationManager() {
    /**
     * Initialize notification channels and categories.
     * Call once during app startup.
     */
    fun initialize()

    /**
     * Request notification permission from the user.
     * No-op on platforms where permission is implicit (Desktop).
     */
    fun requestPermission()

    /**
     * Whether notification permission has been granted.
     * Always true on Desktop.
     */
    val hasPermission: Boolean

    /**
     * Show a notification to the user.
     *
     * @param title Notification title
     * @param body Notification body text
     * @param taskId Associated task ID (for action callbacks)
     * @param isApproval If true, shows approve/deny action buttons (mobile only)
     * @param interruptAction The action being approved (e.g. "commit", "push")
     */
    fun showNotification(
        title: String,
        body: String,
        taskId: String? = null,
        isApproval: Boolean = false,
        interruptAction: String? = null,
        badgeCount: Int? = null,
    )

    /**
     * Cancel/dismiss a notification by task ID.
     */
    fun cancelNotification(taskId: String)
}
