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
 * Třída pro správu ikony aplikace v systémové liště
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
     * Inicializuje systémovou ikonu v liště
     */
    private fun initTrayIcon() {
        if (!SystemTray.isSupported()) {
            logger.warn { "Systémová lišta není podporována na této platformě!" }
            return
        }

        val tray = SystemTray.getSystemTray()
        systemTray = tray

        // Správné načtení ikony z classpath
        val iconInputStream = JervisApplication::class.java.classLoader.getResourceAsStream("icons/jervis_icon.png")
        val image =
            if (iconInputStream != null) {
                try {
                    ImageIO.read(iconInputStream)
                } catch (e: Exception) {
                    logger.error(e) { "Nelze načíst ikonu: ${e.message}" }
                    WindowUtils.createFallbackImage()
                }
            } else {
                logger.warn { "Soubor ikony nebyl nalezen na classpath" }
                WindowUtils.createFallbackImage()
            }

        val popup = PopupMenu()

        val openProjectSettingsItem = MenuItem("Project Settings")
        openProjectSettingsItem.addActionListener {
            windowManager.showProjectSettingWindow()
        }

        val openClientsItem = MenuItem("Client Management")
        openClientsItem.addActionListener {
            windowManager.showClientsWindow()
        }

        val openMainItem = MenuItem("Open Main Window")
        openMainItem.addActionListener {
            windowManager.showMainWindow()
        }

        val exitItem = MenuItem("Exit")
        exitItem.addActionListener {
            System.exit(0)
        }

        popup.add(openMainItem)
        popup.addSeparator()
        popup.add(openProjectSettingsItem)
        popup.add(openClientsItem)
        popup.addSeparator()
        popup.add(exitItem)

        val icon = TrayIcon(image, "JERVIS Assistant", popup)
        trayIcon = icon
        icon.isImageAutoSize = true
        icon.toolTip = "JERVIS Assistant"

        // Přidání posluchače dvojkliku na ikonu - otevře hlavní okno
        icon.addActionListener {
            windowManager.showMainWindow()
        }

        try {
            tray.add(icon)
        } catch (e: AWTException) {
            logger.error(e) { "TrayIcon could not be added: ${e.message}" }
        }
    }

    /**
     * Odstraní ikonu z lišty
     */
    fun dispose() {
        trayIcon?.let { systemTray?.remove(it) }
        trayIcon = null
        systemTray = null
    }
}
