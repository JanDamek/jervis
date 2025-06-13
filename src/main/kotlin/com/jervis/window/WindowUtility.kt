package com.jervis.window

import java.awt.Color
import java.awt.Image
import java.awt.image.BufferedImage

/**
 * Pomocná třída s utilities pro práci s okny
 */
object WindowUtils {
    /**
     * Zjistí, zda běžíme na macOS
     */
    val isMacOS: Boolean = System.getProperty("os.name").lowercase().contains("mac")

    /**
     * Vytvoření náhradní ikony pro případ, že soubor ikony nelze načíst
     */
    fun createFallbackImage(): Image {
        val bufferedImage = BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB)
        val g2d = bufferedImage.createGraphics()
        g2d.color = Color.GREEN
        g2d.fillRect(0, 0, 16, 16)
        g2d.color = Color.BLACK
        g2d.drawRect(0, 0, 15, 15)
        g2d.dispose()
        return bufferedImage
    }
}
