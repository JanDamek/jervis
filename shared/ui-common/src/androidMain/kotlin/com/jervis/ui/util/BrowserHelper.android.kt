package com.jervis.ui.util

import android.content.Intent
import android.net.Uri
import androidx.compose.ui.platform.LocalContext

actual fun openUrlInBrowser(url: String) {
    // Note: This is a simplified version. In real Android app, you need to pass Context.
    // For now, this is a placeholder that won't crash compilation.
    // The actual implementation should use androidx.compose.ui.platform.LocalContext
    // and call context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
}
