package com.jervis.ui.util

import java.io.BufferedReader

actual fun listSystemGpgKeys(): List<SystemGpgKey> {
    return try {
        val process = ProcessBuilder("gpg", "--list-secret-keys", "--keyid-format", "long", "--with-colons")
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use(BufferedReader::readText)
        val exitCode = process.waitFor()
        if (exitCode != 0) return emptyList()
        parseGpgColonOutput(output)
    } catch (_: Exception) {
        emptyList()
    }
}

actual fun exportSystemGpgKey(keyId: String): String? {
    return try {
        val process = ProcessBuilder("gpg", "--export-secret-keys", "--armor", keyId)
            .redirectErrorStream(false)
            .start()
        val output = process.inputStream.bufferedReader().use(BufferedReader::readText)
        val exitCode = process.waitFor()
        if (exitCode != 0 || output.isBlank()) null else output
    } catch (_: Exception) {
        null
    }
}

/**
 * Parses `gpg --list-secret-keys --with-colons` output.
 *
 * Relevant record types:
 * - sec: secret key line — field[4] = keyId
 * - fpr: fingerprint line — field[9] = fingerprint
 * - uid: user ID line — field[9] = "Name <email>"
 */
private fun parseGpgColonOutput(output: String): List<SystemGpgKey> {
    val keys = mutableListOf<SystemGpgKey>()
    var currentKeyId = ""
    var currentFingerprint = ""
    var currentName = ""
    var currentEmail = ""

    for (line in output.lines()) {
        val fields = line.split(":")
        if (fields.size < 2) continue

        when (fields[0]) {
            "sec" -> {
                // Save previous key if complete
                if (currentKeyId.isNotBlank()) {
                    keys.add(SystemGpgKey(currentKeyId, currentFingerprint, currentName, currentEmail))
                }
                currentKeyId = fields.getOrElse(4) { "" }
                currentFingerprint = ""
                currentName = ""
                currentEmail = ""
            }
            "fpr" -> {
                if (currentFingerprint.isBlank()) {
                    currentFingerprint = fields.getOrElse(9) { "" }
                }
            }
            "uid" -> {
                if (currentName.isBlank()) {
                    val uid = fields.getOrElse(9) { "" }
                    val emailMatch = Regex("<(.+?)>").find(uid)
                    currentEmail = emailMatch?.groupValues?.get(1) ?: ""
                    currentName = uid.replace(Regex("\\s*<.+?>\\s*"), "").trim()
                }
            }
        }
    }

    // Don't forget the last key
    if (currentKeyId.isNotBlank()) {
        keys.add(SystemGpgKey(currentKeyId, currentFingerprint, currentName, currentEmail))
    }

    return keys
}
