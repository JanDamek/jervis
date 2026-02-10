package com.jervis.ui.storage

import java.io.File

actual object PendingMessageStorage {
    private val file = File(System.getProperty("user.home"), ".jervis/pending_message.txt")

    actual fun save(message: String?) {
        if (message == null) {
            file.delete()
            return
        }
        file.parentFile.mkdirs()
        file.writeText(message)
    }

    actual fun load(): String? =
        if (file.exists()) file.readText().takeIf { it.isNotBlank() } else null
}
