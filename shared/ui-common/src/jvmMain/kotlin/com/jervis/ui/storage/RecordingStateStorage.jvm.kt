package com.jervis.ui.storage

import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import java.io.File

actual object RecordingStateStorage {
    private val file = File(System.getProperty("user.home"), ".jervis/recording_states.json")
    // Legacy file — read for migration, never written
    private val legacyFile = File(System.getProperty("user.home"), ".jervis/recording_state.json")

    init {
        migrateLegacy()
    }

    /** Migrate single-slot legacy file to list format. */
    private fun migrateLegacy() {
        if (!legacyFile.exists()) return
        try {
            val old = Json.decodeFromString<RecordingState>(legacyFile.readText())
            val current = loadAllInternal().toMutableList()
            if (current.none { it.meetingId == old.meetingId }) {
                current.add(old)
                saveAll(current)
            }
            legacyFile.delete()
        } catch (_: Exception) {
            legacyFile.delete()
        }
    }

    actual fun save(state: RecordingState?) {
        if (state == null) {
            // Legacy compat: clear all
            saveAll(emptyList())
            return
        }
        val current = loadAllInternal().toMutableList()
        val idx = current.indexOfFirst { it.meetingId == state.meetingId }
        if (idx >= 0) {
            current[idx] = state
        } else {
            current.add(state)
        }
        saveAll(current)
    }

    actual fun load(): RecordingState? {
        return loadAllInternal().firstOrNull()
    }

    actual fun loadAll(): List<RecordingState> {
        return loadAllInternal()
    }

    actual fun remove(meetingId: String) {
        val current = loadAllInternal().toMutableList()
        current.removeAll { it.meetingId == meetingId }
        saveAll(current)
    }

    private fun loadAllInternal(): List<RecordingState> {
        if (!file.exists()) return emptyList()
        return try {
            Json.decodeFromString<List<RecordingState>>(file.readText())
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun saveAll(states: List<RecordingState>) {
        if (states.isEmpty()) {
            file.delete()
        } else {
            file.parentFile.mkdirs()
            file.writeText(Json.encodeToString(states))
        }
    }
}
