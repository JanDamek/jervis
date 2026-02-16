package com.jervis.ui.notification

/**
 * Desktop JVM notification manager.
 *
 * Uses platform-specific commands:
 * - macOS: osascript "display notification"
 * - Windows: java.awt.SystemTray
 * - Linux: notify-send
 *
 * Desktop OS notifications don't support action buttons.
 * Approval actions are handled by the in-app ApprovalNotificationDialog.
 */
actual class PlatformNotificationManager actual constructor() {
    private val isMacOS = System.getProperty("os.name")?.lowercase()?.contains("mac") == true
    private val isWindows = System.getProperty("os.name")?.lowercase()?.contains("win") == true
    private val isLinux = System.getProperty("os.name")?.lowercase()?.contains("linux") == true

    actual fun initialize() {
        // Desktop doesn't need channel/category setup
    }

    actual fun requestPermission() {
        // Desktop doesn't require explicit permission
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
    ) {
        try {
            when {
                isMacOS -> showMacOSNotification(title, body)
                isWindows -> showWindowsNotification(title, body)
                isLinux -> showLinuxNotification(title, body)
            }
        } catch (e: Exception) {
            println("Failed to show desktop notification: ${e.message}")
        }

        // If approval → bring app window to front on macOS
        if (isApproval && isMacOS) {
            bringToFront()
        }
    }

    actual fun cancelNotification(taskId: String) {
        // Desktop OS notifications auto-dismiss, no cancel needed
    }

    private fun showMacOSNotification(title: String, body: String) {
        // DISABLED: osascript notifications are associated with Script Editor, not Jervis.
        // Clicking them opens Script Editor instead of bringing Jervis to front.
        // TODO: Implement using terminal-notifier or native NSUserNotificationCenter for proper app association.
        return
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

    /**
     * Bring the application window to the front on macOS.
     * Used when an approval notification arrives to ensure
     * the user sees the in-app approval dialog.
     */
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
