package com.jervis.ui.storage

import platform.Foundation.NSUserDefaults

actual object PendingMessageStorage {
    private const val KEY_MESSAGE = "jervis_pending_message"

    actual fun save(message: String?) {
        val defaults = NSUserDefaults.standardUserDefaults
        if (message == null) {
            defaults.removeObjectForKey(KEY_MESSAGE)
        } else {
            defaults.setObject(message, forKey = KEY_MESSAGE)
        }
    }

    actual fun load(): String? =
        NSUserDefaults.standardUserDefaults.stringForKey(KEY_MESSAGE)?.takeIf { it.isNotBlank() }
}
