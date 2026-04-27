package com.jervis.ui.util

import platform.Foundation.NSLog
import platform.Foundation.NSProcessInfo

actual fun platformLog(
    tag: String,
    message: String,
) {
    NSLog("[%s] %s", tag, message)
}

actual fun getDeviceName(): String = NSProcessInfo.processInfo.hostName
