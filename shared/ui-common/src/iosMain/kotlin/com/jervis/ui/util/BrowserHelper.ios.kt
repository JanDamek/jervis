package com.jervis.ui.util

import platform.Foundation.NSURL
import platform.UIKit.UIApplication

actual fun openUrlInBrowser(url: String) {
    val nsUrl = NSURL.URLWithString(url)
    if (nsUrl != null && UIApplication.sharedApplication.canOpenURL(nsUrl)) {
        UIApplication.sharedApplication.openURL(nsUrl)
    }
}
