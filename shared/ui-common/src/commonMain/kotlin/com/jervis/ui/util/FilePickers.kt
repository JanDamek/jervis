package com.jervis.ui.util

/**
 * Cross-platform file picker utilities for UI needs.
 * Returns file content as text or null if not supported/cancelled.
 */
expect fun pickTextFileContent(title: String = "Select File"): String?
