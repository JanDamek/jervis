package com.jervis.ui.notification

actual class PlatformNotificationManager actual constructor() {
    private val isMacOS = System.getProperty("os.name")?.lowercase()?.contains("mac") == true
    private val isWindows = System.getProperty("os.name")?.lowercase()?.contains("win") == true
    private val isLinux = System.getProperty("os.name")?.lowercase()?.contains("linux") == true

    actual fun initialize() {
    }

    actual fun requestPermission() {
    }

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
        val isUrgent = interruptAction in listOf("o365_mfa", "o365_relogin")
        val category = when {
            isApproval -> "APPROVAL"
            mfaType == "authenticator_code" || mfaType == "sms_code" -> "MFA_CODE"
            mfaType == "authenticator_number" || mfaType == "phone_call" -> "MFA_CONFIRM"
            else -> null
        }

        try {
            when {
                isMacOS -> showMacOSNotification(title, body, taskId, category, mfaNumber)
                isWindows -> showWindowsNotification(title, body)
                isLinux -> showLinuxNotification(title, body)
            }
        } catch (e: Exception) {
            println("Failed to show desktop notification: ${e.message}")
        }

        if ((isApproval || isUrgent) && isMacOS && category == null) {
            bringToFront()
        }
    }

    actual fun cancelNotification(taskId: String) {
        if (isMacOS) {
            MacAppSocketBridge.cancelNotification(taskId)
        }
    }

    private fun showMacOSNotification(title: String, body: String, taskId: String?, category: String?, mfaNumber: String?) {
        val payload = mutableMapOf<String, String>()
        if (mfaNumber != null) payload["mfaNumber"] = mfaNumber
        MacAppSocketBridge.showNotification(
            taskId = taskId,
            title = title,
            body = body,
            category = category,
            payload = payload,
        )
    }

    private fun showWindowsNotification(title: String, body: String) {
        try {
            if (java.awt.SystemTray.isSupported()) {
                val tray = java.awt.SystemTray.getSystemTray()
                val icon = tray.trayIcons.firstOrNull()
                icon?.displayMessage(title, body, java.awt.TrayIcon.MessageType.INFO)
            }
        } catch (e: Exception) {
            println("Windows notification failed: ${e.message}")
        }
    }

    private fun showLinuxNotification(title: String, body: String) {
        Runtime.getRuntime().exec(arrayOf("notify-send", title, body))
    }

    private fun bringToFront() {
        try {
            val script = """
                tell application "System Events"
                    set frontmost of the first process whose unix id is ${ProcessHandle.current().pid()} to true
                end tell
            """.trimIndent()
            Runtime.getRuntime().exec(arrayOf("osascript", "-e", script))
        } catch (e: Exception) {
            println("Failed to bring window to front: ${e.message}")
        }
    }
}
