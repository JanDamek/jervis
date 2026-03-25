package com.jervis.ui.util

import android.util.Log

actual fun platformLog(tag: String, message: String) {
    Log.d(tag, message)
}

actual fun getDeviceName(): String {
    val manufacturer = android.os.Build.MANUFACTURER.replaceFirstChar { it.uppercase() }
    val model = android.os.Build.MODEL
    return if (model.startsWith(manufacturer, ignoreCase = true)) model else "$manufacturer $model"
}
