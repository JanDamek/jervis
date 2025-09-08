package com.jervis.service.mcp.util

import kotlinx.serialization.json.Json

/**
 * Utility for robust JSON parsing in MCP tools.
 * Handles common issues with LLM-generated JSON responses.
 */
object McpJson {
    val parser =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

    /**
     * Cleans common code fence markers from LLM responses.
     */
    fun cleanCodeFence(text: String): String =
        text
            .trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()

    /**
     * Safely decodes JSON string to the specified type.
     * Returns Result for proper error handling.
     */
    inline fun <reified T> decode(answer: String): Result<T> = runCatching { parser.decodeFromString<T>(cleanCodeFence(answer)) }
}
