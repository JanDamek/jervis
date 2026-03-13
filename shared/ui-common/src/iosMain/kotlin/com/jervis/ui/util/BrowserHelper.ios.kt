package com.jervis.ui.util

import platform.Foundation.NSURL
import platform.UIKit.UIApplication

actual fun openUrlInBrowser(url: String) {
    val nsUrl = NSURL.URLWithString(url)
    if (nsUrl != null && UIApplication.sharedApplication.canOpenURL(nsUrl)) {
        UIApplication.sharedApplication.openURL(nsUrl)
    }
}

actual fun openUrlInPrivateBrowser(url: String) {
    // iOS: cannot control private mode, fallback to normal browser
    openUrlInBrowser(url)
}
