package com.jervis.ui.util

/**
 * Platform-aware logging that works in Console.app on iOS (NSLog),
 * Logcat on Android, and stdout on Desktop.
 */
expect fun platformLog(tag: String, message: String)

/**
 * Returns a human-readable device name (e.g. "MacBook Pro", "iPhone 15", "Pixel 8").
 */
expect fun getDeviceName(): String
