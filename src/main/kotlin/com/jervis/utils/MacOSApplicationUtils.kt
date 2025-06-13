package com.jervis.utils

import mu.KotlinLogging
import java.awt.Taskbar
import java.awt.Toolkit
import java.io.IOException
import javax.imageio.ImageIO
import javax.swing.JFrame

/**
 * Pomocná třída pro specifické nastavení aplikace na macOS
 */
object MacOSAppUtils {
    private val logger = KotlinLogging.logger {}
    private val isMacOS = System.getProperty("os.name").lowercase().contains("mac")

    /**
     * Nastaví ikonu aplikace v docku macOS pomocí moderního Taskbar API (Java 9+)
     * @param iconPath Cesta k ikoně (relativní k resources)
     */
    fun setDockIcon() {
        if (!isMacOS) return

        try {
            // Načtení ikony z resources
            val iconInputStream = MacOSAppUtils::class.java.classLoader.getResourceAsStream("icons/jervis_icon.png")
            val image = iconInputStream?.use { ImageIO.read(it) }

            if (image != null && Taskbar.isTaskbarSupported()) {
                val taskbar = Taskbar.getTaskbar()
                if (taskbar.isSupported(Taskbar.Feature.ICON_IMAGE)) {
                    taskbar.iconImage = image
                    logger.info { "Ikona v docku byla úspěšně nastavena pomocí Taskbar API" }
                } else {
                    logger.warn { "Funkce ikony v docku není podporována na tomto systému" }
                }
            } else {
                logger.warn { "Nelze načíst ikonu nebo Taskbar není podporován" }
            }
        } catch (e: IOException) {
            logger.error(e) { "Nelze načíst ikonu: ${e.message}" }
        } catch (e: Exception) {
            logger.error(e) { "Nepodařilo se nastavit ikonu v docku: ${e.message}" }
        }
    }

    /**
     * Nastaví název aplikace v menu a docku
     * @param appName Název aplikace
     */
    fun setAppName(appName: String) {
        if (isMacOS) {
            System.setProperty("apple.awt.application.name", appName)
            logger.info { "Název aplikace nastaven na: $appName" }
        }
    }

    /**
     * Nastaví ikonu pro okno aplikace
     * @param frame Okno, pro které se má nastavit ikona
     * @param iconPath Cesta k ikoně (relativní k resources)
     */
    fun setWindowIcon(
        frame: JFrame,
        iconPath: String,
    ) {
        try {
            val iconURL = MacOSAppUtils::class.java.classLoader.getResource(iconPath)
            if (iconURL != null) {
                val image = Toolkit.getDefaultToolkit().getImage(iconURL)
                frame.iconImage = image
                logger.info { "Ikona okna byla úspěšně nastavena" }
            } else {
                logger.warn { "Ikona nebyla nalezena: $iconPath" }
            }
        } catch (e: Exception) {
            logger.error(e) { "Nepodařilo se nastavit ikonu okna: ${e.message}" }
        }
    }

    /**
     * Zajistí, že okno bude vždy zobrazeno v popředí při aktivaci
     * @param frame Okno, které má být vždy v popředí
     */
    fun ensureWindowVisibility(frame: JFrame) {
        if (!frame.isVisible) {
            frame.isVisible = true
        }

        frame.toFront()
        frame.repaint()

        // Pokud je okno minimalizované, obnovíme ho
        if (frame.state == JFrame.ICONIFIED) {
            frame.state = JFrame.NORMAL
        }
    }

    /**
     * Alternativní metoda pro nastavení ikony v docku pomocí JVM parametrů
     * Tato metoda je pouze informativní, protože JVM parametry je třeba nastavit při spuštění aplikace
     */
    fun getDockIconJvmArgument(iconPath: String): String = "-Xdock:icon=$iconPath"
}
