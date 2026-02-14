package com.jervis.ui.storage

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import platform.Foundation.NSUserDefaults

actual object PendingMessageStorage {
    private const val KEY_STATE = "jervis_pending_state"

    actual fun save(state: PendingMessageState?) {
        val defaults = NSUserDefaults.standardUserDefaults
        if (state == null) {
            defaults.removeObjectForKey(KEY_STATE)
        } else {
            defaults.setObject(Json.encodeToString(state), forKey = KEY_STATE)
        }
    }

    actual fun load(): PendingMessageState? {
        val json = NSUserDefaults.standardUserDefaults.stringForKey(KEY_STATE) ?: return null
        return try {
            Json.decodeFromString<PendingMessageState>(json)
        } catch (_: Exception) {
            null
        }
    }
}
