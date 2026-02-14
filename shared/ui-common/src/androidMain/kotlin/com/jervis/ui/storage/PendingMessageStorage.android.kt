package com.jervis.ui.storage

import android.content.Context
import android.content.SharedPreferences
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

actual object PendingMessageStorage {
    private const val PREFS_NAME = "jervis_pending"
    private const val KEY_STATE = "pending_state"

    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    actual fun save(state: PendingMessageState?) {
        val editor = prefs?.edit() ?: return
        if (state == null) {
            editor.remove(KEY_STATE)
        } else {
            editor.putString(KEY_STATE, Json.encodeToString(state))
        }
        editor.apply()
    }

    actual fun load(): PendingMessageState? {
        val json = prefs?.getString(KEY_STATE, null) ?: return null
        return try {
            Json.decodeFromString<PendingMessageState>(json)
        } catch (_: Exception) {
            null
        }
    }
}
