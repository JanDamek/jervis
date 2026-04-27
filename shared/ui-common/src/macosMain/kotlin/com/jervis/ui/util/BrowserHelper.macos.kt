package com.jervis.ui.util

import platform.AppKit.NSWorkspace
import platform.Foundation.NSURL

actual fun openUrlInBrowser(url: String) {
    val nsUrl = NSURL.URLWithString(url) ?: return
    NSWorkspace.sharedWorkspace().openURL(nsUrl)
}

actual fun openUrlInPrivateBrowser(url: String) {
    // macOS private browsing is tricky to trigger via openURL, fallback to normal
    openUrlInBrowser(url)
}
