package com.jervis.ui.storage

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

actual object OfflineDataCache {
    private val file = File(System.getProperty("user.home"), ".jervis/offline_cache.json")

    actual fun save(data: CachedData) {
        file.parentFile.mkdirs()
        file.writeText(Json.encodeToString(data))
    }

    actual fun load(): CachedData? {
        if (!file.exists()) return null
        return try {
            Json.decodeFromString<CachedData>(file.readText())
        } catch (_: Exception) {
            null
        }
    }
}
