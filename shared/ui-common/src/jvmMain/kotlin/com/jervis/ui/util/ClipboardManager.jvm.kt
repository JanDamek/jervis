package com.jervis.ui.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString

@Composable
actual fun rememberClipboardManager(): ClipboardHandler {
    @Suppress("DEPRECATION")
    val platformClipboard = LocalClipboardManager.current
    return remember {
        object : ClipboardHandler {
            override fun setText(text: AnnotatedString) {
                platformClipboard.setText(text)
            }
        }
    }
}
