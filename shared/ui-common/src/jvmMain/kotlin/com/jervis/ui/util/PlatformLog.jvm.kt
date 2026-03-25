package com.jervis.ui.util

actual fun platformLog(tag: String, message: String) {
    println("[$tag] $message")
}

actual fun getDeviceName(): String {
    return try {
        val os = System.getProperty("os.name", "Desktop")
        val hostname = java.net.InetAddress.getLocalHost().hostName
        "$os ($hostname)"
    } catch (_: Exception) {
        System.getProperty("os.name", "Desktop")
    }
}
