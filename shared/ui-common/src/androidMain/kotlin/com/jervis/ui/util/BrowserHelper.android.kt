package com.jervis.ui.util

import android.content.Intent
import android.net.Uri
import com.jervis.ui.notification.AndroidContextHolder

actual fun openUrlInBrowser(url: String) {
    try {
        val context = AndroidContextHolder.applicationContext
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    } catch (e: Exception) {
        e.printStackTrace()
    }
}

actual fun openUrlInPrivateBrowser(url: String) {
    // Android: cannot control incognito mode, fallback to normal browser
    openUrlInBrowser(url)
}
