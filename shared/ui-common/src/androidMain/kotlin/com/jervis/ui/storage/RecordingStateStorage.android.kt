package com.jervis.ui.storage

import android.content.Context
import android.content.SharedPreferences
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString

actual object RecordingStateStorage {
    private const val PREFS_NAME = "jervis_recording"
    private const val KEY_STATES = "recording_states"
    // Legacy key — read for migration, never written
    private const val KEY_STATE_LEGACY = "recording_state"

    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        migrateLegacy()
    }

    /** Migrate single-slot legacy format to list format. */
    private fun migrateLegacy() {
        val p = prefs ?: return
        val legacyJson = p.getString(KEY_STATE_LEGACY, null) ?: return
        try {
            val old = Json.decodeFromString<RecordingState>(legacyJson)
            val current = loadAllInternal().toMutableList()
            if (current.none { it.meetingId == old.meetingId }) {
                current.add(old)
                saveAll(current)
            }
            p.edit().remove(KEY_STATE_LEGACY).apply()
        } catch (_: Exception) {
            p.edit().remove(KEY_STATE_LEGACY).apply()
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
        val json = prefs?.getString(KEY_STATES, null) ?: return emptyList()
        return try {
            Json.decodeFromString<List<RecordingState>>(json)
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun saveAll(states: List<RecordingState>) {
        val editor = prefs?.edit() ?: return
        if (states.isEmpty()) {
            editor.remove(KEY_STATES)
        } else {
            editor.putString(KEY_STATES, Json.encodeToString(states))
        }
        editor.apply()
    }
}
