package com.jervis.rag._internal

import org.bson.types.ObjectId

object WeaviateClassNameUtil {
    fun textClassFor(clientId: ObjectId): String = buildClassNameFromId(clientId.toHexString(), "RagText")
    fun codeClassFor(clientId: ObjectId): String = buildClassNameFromId(clientId.toHexString(), "RagCode")

    fun textClassFor(clientIdHex: String): String = buildClassNameFromId(clientIdHex, "RagText")
    fun codeClassFor(clientIdHex: String): String = buildClassNameFromId(clientIdHex, "RagCode")

    private fun buildClassNameFromId(clientIdHex: String, suffix: String): String {
        // Weaviate class name rules: must start with letter, only letters/numbers allowed
        // Use ObjectId hex and prefix with 'C'
        val cleaned = clientIdHex.filter { it.isLetterOrDigit() }
        val base = if (cleaned.isNotBlank()) cleaned else "X"
        val start = if (base.first().isLetter()) base else "C$base"
        // PascalCase-ify minimal: ensure first letter upper-case
        val pascal = start.replaceFirstChar { it.uppercase() }
        return pascal + suffix
    }
}
