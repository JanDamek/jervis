package com.jervis.ui.storage

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

actual object RecordingSessionStorage {
    private val file = File(System.getProperty("user.home"), ".jervis/recording_sessions.json")

    actual fun save(sessions: List<RecordingSession>) {
        file.parentFile.mkdirs()
        file.writeText(Json.encodeToString(sessions))
    }

    actual fun load(): List<RecordingSession> {
        if (!file.exists()) return emptyList()
        return try {
            Json.decodeFromString<List<RecordingSession>>(file.readText())
        } catch (_: Exception) {
            emptyList()
        }
    }
}
