package com.jervis.desktop

import java.awt.Desktop
import java.awt.Taskbar
import java.awt.desktop.AppReopenedListener
import javax.imageio.ImageIO

/**
 * Utilities for macOS-specific features like dock badge
 */
object MacOSUtils {
    private val isMacOS = System.getProperty("os.name").lowercase().contains("mac")
    private var dockIconSet = false

    /**
     * Set dock icon (macOS only)
     * Should be called at app startup
     */
    fun setDockIcon() {
        if (!isMacOS || dockIconSet) return

        try {
            val iconStream = MacOSUtils::class.java.classLoader.getResourceAsStream("icons/jervis_icon.png")
            val image = iconStream?.use { ImageIO.read(it) }

            if (image != null && Taskbar.isTaskbarSupported()) {
                val taskbar = Taskbar.getTaskbar()
                if (taskbar.isSupported(Taskbar.Feature.ICON_IMAGE)) {
                    taskbar.iconImage = image
                    dockIconSet = true
                    println("Dock icon set successfully")
                }
            }
        } catch (e: Exception) {
            println("Failed to set dock icon: ${e.message}")
        }
    }

    /**
     * Register handler for dock icon click (macOS only)
     * Calls the provided callback when user clicks on dock icon
     */
    fun setDockIconClickHandler(onDockIconClick: () -> Unit) {
        if (!isMacOS) return

        try {
            if (Desktop.isDesktopSupported()) {
                val desktop = Desktop.getDesktop()
                desktop.setOpenURIHandler { null } // Enable app reopening
                desktop.addAppEventListener(AppReopenedListener {
                    onDockIconClick()
                })
                println("Dock icon click handler registered")
            }
        } catch (e: Exception) {
            println("Failed to register dock icon click handler: ${e.message}")
        }
    }

    /**
     * Set dock badge count (macOS only)
     * Shows a red badge with number on the dock icon
     */
    fun setDockBadgeCount(count: Int) {
        if (!isMacOS) return

        try {
            if (Taskbar.isTaskbarSupported()) {
                val taskbar = Taskbar.getTaskbar()
                if (taskbar.isSupported(Taskbar.Feature.ICON_BADGE_TEXT)) {
                    val text = if (count > 0) count.toString() else null
                    // Use reflection to call setIconBadge (macOS-specific method)
                    val method = taskbar.javaClass.methods.firstOrNull {
                        it.name == "setIconBadge" && it.parameterCount == 1
                    }
                    method?.invoke(taskbar, text)
                }
            }
        } catch (e: Exception) {
            println("Failed to update dock badge: ${e.message}")
        }
    }

    /**
     * Show macOS system notification
     *
     * DISABLED: osascript notifications are associated with Script Editor, not Jervis.
     * Clicking them opens Script Editor instead of bringing Jervis to front.
     * TODO: Implement using terminal-notifier or native NSUserNotificationCenter for proper app association.
     */
    fun showNotification(title: String, message: String) {
        // Disabled - see comment above
        return
    }
}
