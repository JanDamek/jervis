package com.jervis.ui.util

import java.awt.Desktop
import java.net.URI

actual fun openUrlInBrowser(url: String) {
    try {
        if (Desktop.isDesktopSupported()) {
            Desktop.getDesktop().browse(URI(url))
        } else {
            // Fallback for systems without Desktop support
            val os = System.getProperty("os.name").lowercase()
            when {
                os.contains("win") -> Runtime.getRuntime().exec("rundll32 url.dll,FileProtocolHandler $url")
                os.contains("mac") -> Runtime.getRuntime().exec("open $url")
                else -> Runtime.getRuntime().exec("xdg-open $url")
            }
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
}
