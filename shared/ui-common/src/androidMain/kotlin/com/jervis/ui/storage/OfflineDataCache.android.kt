package com.jervis.ui.storage

import android.content.Context
import android.content.SharedPreferences
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

actual object OfflineDataCache {
    private const val PREFS_NAME = "jervis_offline_cache"
    private const val KEY_DATA = "cached_data"

    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    actual fun save(data: CachedData) {
        prefs?.edit()?.putString(KEY_DATA, Json.encodeToString(data))?.apply()
    }

    actual fun load(): CachedData? {
        val json = prefs?.getString(KEY_DATA, null) ?: return null
        return try {
            Json.decodeFromString<CachedData>(json)
        } catch (_: Exception) {
            null
        }
    }
}
