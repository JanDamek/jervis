package com.jervis.ui.utils

import com.jervis.ui.component.ApplicationWindowManager
import mu.KotlinLogging
import java.awt.Desktop
import java.awt.PopupMenu
import java.awt.Taskbar
import java.io.IOException
import javax.imageio.ImageIO
import javax.swing.JMenu
import javax.swing.JMenuBar
import javax.swing.JMenuItem

/**
 * Utility for macOS-specific application configuration and native menu integration.
 */
object MacOSAppUtils {
    private val logger = KotlinLogging.logger {}
    private val isMacOS = System.getProperty("os.name").lowercase().contains("mac")
    private var windowManager: ApplicationWindowManager? = null

    /**
     * Sets the application icon in the macOS Dock using the modern Taskbar API (Java 9+).
     */
    fun setDockIcon() {
        if (!isMacOS) return

        try {
            // Load icon from resources
            val iconInputStream = MacOSAppUtils::class.java.classLoader.getResourceAsStream("icons/jervis_icon.png")
            val image = iconInputStream?.use { ImageIO.read(it) }

            if (image != null && Taskbar.isTaskbarSupported()) {
                val taskbar = Taskbar.getTaskbar()
                if (taskbar.isSupported(Taskbar.Feature.ICON_IMAGE)) {
                    taskbar.iconImage = image
                    logger.info { "Dock icon was successfully set using the Taskbar API" }
                } else {
                    logger.warn { "Taskbar feature ICON_IMAGE is not supported on this system" }
                }
            } else {
                logger.warn { "Icon could not be loaded or Taskbar is not supported" }
            }
        } catch (e: IOException) {
            logger.error(e) { "Failed to load icon: ${e.message}" }
        } catch (e: Exception) {
            logger.error(e) { "Failed to set dock icon via Taskbar API: ${e.message}" }
        }
    }

    /**
     * Configures macOS-specific settings for native menu bar integration.
     */
    fun configureMacOSSettings() {
        if (!isMacOS) return

        try {
            // Enable native macOS menu bar
            System.setProperty("apple.laf.useScreenMenuBar", "true")
            System.setProperty("apple.awt.application.name", "JERVIS")
            System.setProperty("com.apple.macos.useScreenMenuBar", "true")

            logger.info { "macOS native menu bar settings configured successfully" }
        } catch (e: Exception) {
            logger.error(e) { "Failed to configure macOS settings: ${e.message}" }
        }
    }

    /**
     * Sets up the native macOS menu bar with all application functionality.
     */
    fun setupNativeMenuBar(applicationWindowManager: ApplicationWindowManager): JMenuBar? {
        if (!isMacOS) return null

        windowManager = applicationWindowManager

        try {
            val menuBar = JMenuBar()

            // Application menu (JERVIS) - handled automatically by macOS
            // File menu
            val fileMenu = JMenu("File")

            val openMainWindowItem = JMenuItem("Open Main Window")
            openMainWindowItem.addActionListener { applicationWindowManager.showMainWindow() }
            fileMenu.add(openMainWindowItem)

            fileMenu.addSeparator()

            // Exit is handled automatically by macOS in the application menu

            // Tools menu
            val toolsMenu = JMenu("Tools")

            val projectSettingsItem = JMenuItem("Project Settings")
            projectSettingsItem.addActionListener { applicationWindowManager.showProjectSettingWindow() }
            toolsMenu.add(projectSettingsItem)

            val clientManagementItem = JMenuItem("Client Management")
            clientManagementItem.addActionListener { applicationWindowManager.showClientsWindow() }
            toolsMenu.add(clientManagementItem)

            val schedulerItem = JMenuItem("Scheduler")
            schedulerItem.addActionListener { applicationWindowManager.showSchedulerWindow() }
            toolsMenu.add(schedulerItem)

            toolsMenu.addSeparator()

            val indexingMonitorItem = JMenuItem("Indexing Monitor")
            indexingMonitorItem.addActionListener { applicationWindowManager.showIndexingMonitor() }
            toolsMenu.add(indexingMonitorItem)

            val debugWindowItem = JMenuItem("Show Debug Window")
            debugWindowItem.addActionListener { applicationWindowManager.showDebugWindow() }
            toolsMenu.add(debugWindowItem)

            // Window menu - handled automatically by macOS but we can add custom items
            val windowMenu = JMenu("Window")
            // macOS will automatically add minimize, zoom, etc.

            // Help menu
            val helpMenu = JMenu("Help")
            val aboutItem = JMenuItem("About JERVIS")
            aboutItem.addActionListener { showAboutDialog() }
            helpMenu.add(aboutItem)

            menuBar.add(fileMenu)
            menuBar.add(toolsMenu)
            menuBar.add(windowMenu)
            menuBar.add(helpMenu)

            logger.info { "Native macOS menu bar setup completed successfully" }
            return menuBar
        } catch (e: Exception) {
            logger.error(e) { "Failed to setup native macOS menu bar: ${e.message}" }
            return null
        }
    }

    /**
     * Sets up dock context menu using Taskbar API.
     */
    fun setupDockContextMenu(applicationWindowManager: ApplicationWindowManager) {
        if (!isMacOS) return

        try {
            if (Taskbar.isTaskbarSupported()) {
                val taskbar = Taskbar.getTaskbar()

                if (taskbar.isSupported(Taskbar.Feature.MENU)) {
                    val dockMenu = PopupMenu()

                    val openMainItem = java.awt.MenuItem("Open Main Window")
                    openMainItem.addActionListener { applicationWindowManager.showMainWindow() }
                    dockMenu.add(openMainItem)

                    dockMenu.addSeparator()

                    val projectSettingsItem = java.awt.MenuItem("Project Settings")
                    projectSettingsItem.addActionListener { applicationWindowManager.showProjectSettingWindow() }
                    dockMenu.add(projectSettingsItem)

                    val clientManagementItem = java.awt.MenuItem("Client Management")
                    clientManagementItem.addActionListener { applicationWindowManager.showClientsWindow() }
                    dockMenu.add(clientManagementItem)

                    val schedulerItem = java.awt.MenuItem("Scheduler")
                    schedulerItem.addActionListener { applicationWindowManager.showSchedulerWindow() }
                    dockMenu.add(schedulerItem)

                    val indexingMonitorItem = java.awt.MenuItem("Indexing Monitor")
                    indexingMonitorItem.addActionListener { applicationWindowManager.showIndexingMonitor() }
                    dockMenu.add(indexingMonitorItem)

                    val debugWindowItem = java.awt.MenuItem("Show Debug Window")
                    debugWindowItem.addActionListener { applicationWindowManager.showDebugWindow() }
                    dockMenu.add(debugWindowItem)

                    dockMenu.addSeparator()

                    val exitItem = java.awt.MenuItem("Exit")
                    exitItem.addActionListener { System.exit(0) }
                    dockMenu.add(exitItem)

                    taskbar.menu = dockMenu
                    logger.info { "Dock context menu setup completed successfully" }
                } else {
                    logger.warn { "Taskbar MENU feature is not supported on this system" }
                }
            } else {
                logger.warn { "Taskbar is not supported on this system" }
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to setup dock context menu: ${e.message}" }
        }
    }

    /**
     * Shows about dialog for the application.
     */
    private fun showAboutDialog() {
        try {
            if (Desktop.isDesktopSupported()) {
                // This could be enhanced with a proper About dialog
                // For now, we'll delegate to the main window if available
                windowManager?.showMainWindow()
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to show about dialog: ${e.message}" }
        }
    }
}
