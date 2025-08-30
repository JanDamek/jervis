package com.jervis.ui.utils

import mu.KotlinLogging
import java.awt.Taskbar
import java.io.IOException
import javax.imageio.ImageIO

/**
 * Utility for macOS-specific application configuration.
 */
object MacOSAppUtils {
    private val logger = KotlinLogging.logger {}
    private val isMacOS = System.getProperty("os.name").lowercase().contains("mac")

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
}
