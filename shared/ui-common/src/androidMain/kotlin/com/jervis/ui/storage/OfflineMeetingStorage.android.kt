package com.jervis.ui.storage

import android.content.Context
import android.content.SharedPreferences
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

actual object OfflineMeetingStorage {
    private const val PREFS_NAME = "jervis_offline_meetings"
    private const val KEY_DATA = "meetings"

    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    actual fun save(meetings: List<OfflineMeeting>) {
        prefs?.edit()?.putString(KEY_DATA, Json.encodeToString(meetings))?.apply()
    }

    actual fun load(): List<OfflineMeeting> {
        val json = prefs?.getString(KEY_DATA, null) ?: return emptyList()
        return try {
            Json.decodeFromString<List<OfflineMeeting>>(json)
        } catch (_: Exception) {
            emptyList()
        }
    }
}
