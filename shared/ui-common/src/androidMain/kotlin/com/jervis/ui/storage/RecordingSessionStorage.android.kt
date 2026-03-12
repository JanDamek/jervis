package com.jervis.ui.storage

import android.content.Context
import android.content.SharedPreferences
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

actual object RecordingSessionStorage {
    private const val PREFS_NAME = "jervis_recording_sessions"
    private const val KEY_DATA = "sessions"

    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    actual fun save(sessions: List<RecordingSession>) {
        prefs?.edit()?.putString(KEY_DATA, Json.encodeToString(sessions))?.apply()
    }

    actual fun load(): List<RecordingSession> {
        val json = prefs?.getString(KEY_DATA, null) ?: return emptyList()
        return try {
            Json.decodeFromString<List<RecordingSession>>(json)
        } catch (_: Exception) {
            emptyList()
        }
    }
}
