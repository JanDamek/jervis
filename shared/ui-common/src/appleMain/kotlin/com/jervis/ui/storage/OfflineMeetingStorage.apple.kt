package com.jervis.ui.storage

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import platform.Foundation.NSUserDefaults

actual object OfflineMeetingStorage {
    private const val KEY_DATA = "jervis_offline_meetings_data"

    actual fun save(meetings: List<OfflineMeeting>) {
        NSUserDefaults.standardUserDefaults.setObject(Json.encodeToString(meetings), forKey = KEY_DATA)
    }

    actual fun load(): List<OfflineMeeting> {
        val json = NSUserDefaults.standardUserDefaults.stringForKey(KEY_DATA) ?: return emptyList()
        return try {
            Json.decodeFromString<List<OfflineMeeting>>(json)
        } catch (_: Exception) {
            emptyList()
        }
    }
}
