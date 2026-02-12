package com.jervis.ui.storage

import android.content.Context
import android.content.SharedPreferences
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString

actual object RecordingStateStorage {
    private const val PREFS_NAME = "jervis_recording"
    private const val KEY_STATE = "recording_state"

    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    actual fun save(state: RecordingState?) {
        val editor = prefs?.edit() ?: return
        if (state == null) {
            editor.remove(KEY_STATE)
        } else {
            editor.putString(KEY_STATE, Json.encodeToString(state))
        }
        editor.apply()
    }

    actual fun load(): RecordingState? {
        val json = prefs?.getString(KEY_STATE, null) ?: return null
        return try {
            Json.decodeFromString<RecordingState>(json)
        } catch (_: Exception) {
            null
        }
    }
}
