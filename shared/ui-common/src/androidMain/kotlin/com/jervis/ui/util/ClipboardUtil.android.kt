package com.jervis.ui.util

// TODO: Requires Android Context - wire from app layer if needed
actual fun copyToClipboard(text: String) {
    // No-op on Android common module - clipboard needs Activity context
}
