package com.jervis.ui.storage

import android.content.Context
import android.content.SharedPreferences

actual object PendingMessageStorage {
    private const val PREFS_NAME = "jervis_pending"
    private const val KEY_MESSAGE = "pending_message"

    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    actual fun save(message: String?) {
        val editor = prefs?.edit() ?: return
        if (message == null) {
            editor.remove(KEY_MESSAGE)
        } else {
            editor.putString(KEY_MESSAGE, message)
        }
        editor.apply()
    }

    actual fun load(): String? =
        prefs?.getString(KEY_MESSAGE, null)?.takeIf { it.isNotBlank() }
}
