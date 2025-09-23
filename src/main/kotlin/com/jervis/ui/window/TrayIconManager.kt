package com.jervis.ui.window

import com.jervis.JervisApplication
import com.jervis.ui.component.ApplicationWindowManager
import mu.KotlinLogging
import java.awt.AWTException
import java.awt.MenuItem
import java.awt.PopupMenu
import java.awt.SystemTray
import java.awt.TrayIcon
import javax.imageio.ImageIO

/**
 * Class for managing the application icon in the system tray
 */
class TrayIconManager {
    private val logger = KotlinLogging.logger {}
    private var trayIcon: TrayIcon? = null
    private var systemTray: SystemTray? = null
    private val windowManager: ApplicationWindowManager

    constructor(windowManager: ApplicationWindowManager) {
        this.windowManager = windowManager
        initTrayIcon()
    }

    /**
     * Initializes the system tray icon
     */
    private fun initTrayIcon() {
        if (!SystemTray.isSupported()) {
            logger.warn { "System tray is not supported on this platform!" }
            return
        }

        val tray = SystemTray.getSystemTray()
        systemTray = tray

        // Load icon from classpath - fail if not available
        val iconInputStream =
            JervisApplication::class.java.classLoader.getResourceAsStream("icons/jervis_icon.png")
                ?: throw IllegalStateException("Icon file 'icons/jervis_icon.png' was not found on classpath")

        val image =
            try {
                ImageIO.read(iconInputStream)
            } catch (e: Exception) {
                throw IllegalStateException("Cannot load icon from classpath: ${e.message}", e)
            } ?: throw IllegalStateException("Failed to read icon image from input stream")

        val popup = PopupMenu()

        val openMainItem = MenuItem("Open Main Window")
        openMainItem.addActionListener {
            windowManager.showMainWindow()
        }

        // Management items
        val openProjectSettingsItem = MenuItem("Project Settings")
        openProjectSettingsItem.addActionListener {
            windowManager.showProjectSettingWindow()
        }

        val openClientsItem = MenuItem("Client Management")
        openClientsItem.addActionListener {
            windowManager.showClientsWindow()
        }

        val openSchedulerItem = MenuItem("Scheduler")
        openSchedulerItem.addActionListener {
            windowManager.showSchedulerWindow()
        }

        val indexingMonitorItem = MenuItem("Indexing Monitor")
        indexingMonitorItem.addActionListener {
            windowManager.showIndexingMonitor()
        }

        val exitItem = MenuItem("Exit")
        exitItem.addActionListener {
            System.exit(0)
        }

        popup.add(openMainItem)
        popup.addSeparator()
        popup.add(openProjectSettingsItem)
        popup.add(openClientsItem)
        popup.add(openSchedulerItem)
        popup.add(indexingMonitorItem)
        popup.addSeparator()
        popup.add(exitItem)

        val icon = TrayIcon(image, "JERVIS Assistant", popup)
        trayIcon = icon
        icon.isImageAutoSize = true
        icon.toolTip = "JERVIS Assistant"

        // Add double-click listener to icon - opens main window
        icon.addActionListener {
            windowManager.showMainWindow()
        }

        try {
            tray.add(icon)
            logger.info { "Tray icon successfully added to system tray" }
        } catch (e: AWTException) {
            logger.error(e) { "TrayIcon could not be added: ${e.message}" }
            throw IllegalStateException("Failed to add tray icon to system tray", e)
        }
    }

    /**
     * Removes the icon from the tray
     */
    fun dispose() {
        trayIcon?.let { systemTray?.remove(it) }
        trayIcon = null
        systemTray = null
    }
}
