package com.jervis.ui.storage

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import platform.Foundation.NSUserDefaults

actual object OfflineDataCache {
    private const val KEY_DATA = "jervis_offline_cache_data"

    actual fun save(data: CachedData) {
        NSUserDefaults.standardUserDefaults.setObject(Json.encodeToString(data), forKey = KEY_DATA)
    }

    actual fun load(): CachedData? {
        val json = NSUserDefaults.standardUserDefaults.stringForKey(KEY_DATA) ?: return null
        return try {
            Json.decodeFromString<CachedData>(json)
        } catch (_: Exception) {
            null
        }
    }
}
