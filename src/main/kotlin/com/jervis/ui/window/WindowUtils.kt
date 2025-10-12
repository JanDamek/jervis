package com.jervis.ui.window

/**
 * Utility class with utilities for working with windows
 */
object WindowUtils {
    /**
     * Determines if we are running on macOS
     */
    val isMacOS: Boolean = System.getProperty("os.name").lowercase().contains("mac")
}
