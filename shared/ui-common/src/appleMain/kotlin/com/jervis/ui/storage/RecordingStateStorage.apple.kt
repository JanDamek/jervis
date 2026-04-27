package com.jervis.ui.storage

import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import platform.Foundation.NSUserDefaults

actual object RecordingStateStorage {
    private const val KEY_STATES = "jervis_recording_states"
    // Legacy key — read for migration, never written
    private const val KEY_STATE_LEGACY = "jervis_recording_state"

    init {
        migrateLegacy()
    }

    /** Migrate single-slot legacy format to list format. */
    private fun migrateLegacy() {
        val defaults = NSUserDefaults.standardUserDefaults
        val legacyJson = defaults.stringForKey(KEY_STATE_LEGACY) ?: return
        try {
            val old = Json.decodeFromString<RecordingState>(legacyJson)
            val current = loadAllInternal().toMutableList()
            if (current.none { it.meetingId == old.meetingId }) {
                current.add(old)
                saveAll(current)
            }
            defaults.removeObjectForKey(KEY_STATE_LEGACY)
        } catch (_: Exception) {
            defaults.removeObjectForKey(KEY_STATE_LEGACY)
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
        val json = NSUserDefaults.standardUserDefaults.stringForKey(KEY_STATES) ?: return emptyList()
        return try {
            Json.decodeFromString<List<RecordingState>>(json)
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun saveAll(states: List<RecordingState>) {
        val defaults = NSUserDefaults.standardUserDefaults
        if (states.isEmpty()) {
            defaults.removeObjectForKey(KEY_STATES)
        } else {
            defaults.setObject(Json.encodeToString(states), forKey = KEY_STATES)
        }
    }
}
