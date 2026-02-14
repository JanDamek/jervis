package com.jervis.ui.storage

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

actual object PendingMessageStorage {
    private val file = File(System.getProperty("user.home"), ".jervis/pending_message.json")

    actual fun save(state: PendingMessageState?) {
        if (state == null) {
            file.delete()
            return
        }
        file.parentFile.mkdirs()
        file.writeText(Json.encodeToString(state))
    }

    actual fun load(): PendingMessageState? {
        if (!file.exists()) return null
        return try {
            Json.decodeFromString<PendingMessageState>(file.readText())
        } catch (_: Exception) {
            null
        }
    }
}
