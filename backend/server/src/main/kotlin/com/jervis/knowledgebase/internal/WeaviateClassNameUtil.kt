package com.jervis.knowledgebase.internal

import com.jervis.types.ClientId

object WeaviateClassNameUtil {
    fun classFor(clientId: ClientId): String = buildClassNameFromId(clientId.toString())

    fun classFor(clientIdHex: String): String = buildClassNameFromId(clientIdHex)

    private fun buildClassNameFromId(clientIdHex: String): String {
        val cleaned = clientIdHex.filter { it.isLetterOrDigit() }
        val base = cleaned.ifBlank { "X" }
        val start = if (base.first().isLetter()) base else "C$base"
        val pascal = start.replaceFirstChar { it.uppercase() }
        return pascal + "Rag"
    }
}
