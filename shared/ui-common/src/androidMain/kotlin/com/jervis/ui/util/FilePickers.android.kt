package com.jervis.ui.util

// TODO: Desktop-only feature - File picker not available in common Android module
// Consider integrating Activity Result API from app layer if needed.
actual fun pickTextFileContent(title: String): String? = null

// TODO: Implement with Activity Result API from the Android app layer
actual fun pickFile(title: String): PickedFile? = null
