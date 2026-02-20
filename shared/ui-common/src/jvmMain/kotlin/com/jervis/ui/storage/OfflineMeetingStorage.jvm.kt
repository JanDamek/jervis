package com.jervis.ui.storage

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

actual object OfflineMeetingStorage {
    private val file = File(System.getProperty("user.home"), ".jervis/offline_meetings.json")

    actual fun save(meetings: List<OfflineMeeting>) {
        file.parentFile.mkdirs()
        file.writeText(Json.encodeToString(meetings))
    }

    actual fun load(): List<OfflineMeeting> {
        if (!file.exists()) return emptyList()
        return try {
            Json.decodeFromString<List<OfflineMeeting>>(file.readText())
        } catch (_: Exception) {
            emptyList()
        }
    }
}
