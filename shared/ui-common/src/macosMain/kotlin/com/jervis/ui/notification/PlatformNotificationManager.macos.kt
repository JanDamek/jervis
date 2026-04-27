package com.jervis.ui.notification

actual class PlatformNotificationManager actual constructor() {
    actual fun initialize() {}

    actual fun requestPermission() {}

    actual val hasPermission: Boolean
        get() = true

    actual fun showNotification(
        title: String,
        body: String,
        taskId: String?,
        isApproval: Boolean,
        interruptAction: String?,
        badgeCount: Int?,
        mfaType: String?,
        mfaNumber: String?,
        requestId: String?,
    ) {
        // TODO: Implement macOS notifications
    }

    actual fun cancelNotification(taskId: String) {}
}
