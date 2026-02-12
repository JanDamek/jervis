package com.jervis.ui.storage

import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import java.io.File

actual object RecordingStateStorage {
    private val file = File(System.getProperty("user.home"), ".jervis/recording_state.json")

    actual fun save(state: RecordingState?) {
        if (state == null) {
            file.delete()
            return
        }
        file.parentFile.mkdirs()
        file.writeText(Json.encodeToString(state))
    }

    actual fun load(): RecordingState? {
        if (!file.exists()) return null
        return try {
            Json.decodeFromString<RecordingState>(file.readText())
        } catch (_: Exception) {
            null
        }
    }
}
