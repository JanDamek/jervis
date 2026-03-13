package com.jervis.ui.util

import java.awt.Desktop
import java.io.File
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

actual fun openUrlInPrivateBrowser(url: String) {
    val os = System.getProperty("os.name").lowercase()
    if (os.contains("mac")) {
        openUrlInPrivateBrowserMacOs(url)
    } else {
        // Non-macOS: fallback to normal browser
        openUrlInBrowser(url)
    }
}

private fun openUrlInPrivateBrowserMacOs(url: String) {
    try {
        // Try browsers in order: Chrome incognito → Firefox private → Brave incognito → fallback
        val browsers = listOf(
            BrowserConfig("/Applications/Google Chrome.app", arrayOf("open", "-na", "Google Chrome", "--args", "--incognito", url)),
            BrowserConfig("/Applications/Firefox.app", arrayOf("open", "-na", "Firefox", "--args", "-private-window", url)),
            BrowserConfig("/Applications/Brave Browser.app", arrayOf("open", "-na", "Brave Browser", "--args", "--incognito", url)),
        )
        for (browser in browsers) {
            if (File(browser.appPath).exists()) {
                Runtime.getRuntime().exec(browser.command)
                return
            }
        }
        // No known browser found — fallback to default
        openUrlInBrowser(url)
    } catch (e: Exception) {
        e.printStackTrace()
        openUrlInBrowser(url)
    }
}

private data class BrowserConfig(val appPath: String, val command: Array<String>)
