package com.jervis.ui.util

import platform.UIKit.UIApplication
import platform.Foundation.NSURL

/**
 * iOS implementation of browser opener
 */
actual fun openUrlInBrowser(url: String) {
    try {
        val nsUrl = NSURL(string = url)
        if (nsUrl != null) {
            UIApplication.sharedApplication.openURL(nsUrl)
        }
    } catch (e: Exception) {
        println("Failed to open browser: ${e.message}")
    }
}
