package com.jervis.ui.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.AnnotatedString
import platform.AppKit.NSPasteboard
import platform.AppKit.NSPasteboardTypeString

actual fun copyToClipboard(text: String) {
    val pasteboard = NSPasteboard.generalPasteboard()
    pasteboard.clearContents()
    pasteboard.setString(text, forType = NSPasteboardTypeString)
}

class MacosClipboardHandler : ClipboardHandler {
    override fun setText(text: AnnotatedString) {
        copyToClipboard(text.text)
    }
}

@Composable
actual fun rememberClipboardManager(): ClipboardHandler = remember { MacosClipboardHandler() }
