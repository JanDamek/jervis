package com.jervis.ui.storage

import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import platform.Foundation.NSUserDefaults

actual object RecordingStateStorage {
    private const val KEY_STATE = "jervis_recording_state"

    actual fun save(state: RecordingState?) {
        val defaults = NSUserDefaults.standardUserDefaults
        if (state == null) {
            defaults.removeObjectForKey(KEY_STATE)
        } else {
            defaults.setObject(Json.encodeToString(state), forKey = KEY_STATE)
        }
    }

    actual fun load(): RecordingState? {
        val json = NSUserDefaults.standardUserDefaults.stringForKey(KEY_STATE) ?: return null
        return try {
            Json.decodeFromString<RecordingState>(json)
        } catch (_: Exception) {
            null
        }
    }
}
