package com.jervis.ui.util

import java.awt.Desktop
import java.net.URI

/**
 * Desktop (JVM) implementation of browser opener
 */
fun openUrlInBrowser(url: String) {
    try {
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            Desktop.getDesktop().browse(URI(url))
        }
    } catch (e: Exception) {
        System.err.println("Failed to open browser: ${e.message}")
    }
}

/**
 * Desktop (JVM) implementation of server base URL getter
 * Returns configured server URL or localhost for development
 */
fun getServerBaseUrl(): String {
    // Try to get from environment or system property first
    val envUrl = System.getenv("JERVIS_SERVER_URL")
    if (!envUrl.isNullOrBlank()) {
        return envUrl
    }
    
    val propUrl = System.getProperty("jervis.server.url")
    if (!propUrl.isNullOrBlank()) {
        return propUrl
    }
    
    // Default to localhost for development
    return System.getProperty("jervis.server.url", "http://localhost:8080")
}
