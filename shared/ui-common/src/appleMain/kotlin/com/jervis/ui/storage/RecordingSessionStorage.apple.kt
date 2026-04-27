package com.jervis.ui.storage

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import platform.Foundation.NSUserDefaults

actual object RecordingSessionStorage {
    private const val KEY_DATA = "jervis_recording_sessions"

    actual fun save(sessions: List<RecordingSession>) {
        NSUserDefaults.standardUserDefaults.setObject(Json.encodeToString(sessions), forKey = KEY_DATA)
    }

    actual fun load(): List<RecordingSession> {
        val json = NSUserDefaults.standardUserDefaults.stringForKey(KEY_DATA) ?: return emptyList()
        return try {
            Json.decodeFromString<List<RecordingSession>>(json)
        } catch (_: Exception) {
            emptyList()
        }
    }
}
