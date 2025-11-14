package com.jervis.ui.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.AnnotatedString
import platform.UIKit.UIPasteboard

@Composable
actual fun rememberClipboardManager(): ClipboardHandler {
    return remember {
        object : ClipboardHandler {
            override fun setText(text: AnnotatedString) {
                UIPasteboard.generalPasteboard.string = text.text
            }
        }
    }
}
