package com.jervis.repository.vector

import com.jervis.domain.rag.RagDocument
import io.weaviate.client.v1.graphql.model.GraphQLResponse
import io.weaviate.client.base.Result as WeaviateResult

/**
 * Extension functions for mapping between domain models and Weaviate structures.
 * Provides clean, readable conversions without verbose builders.
 */

/**
 * Convert RagDocument to Weaviate properties map
 */
fun RagDocument.toWeaviateProperties(): Map<String, Any> =
    buildMap {
        // Required fields
        put("text", text)
        // Store IDs as hex strings to match filters coming from REST/UI
        put("clientId", clientId.toHexString())
        put("ragSourceType", ragSourceType.name)
        put("branch", branch)

        // Optional fields (only add if non-null)
        projectId?.let { put("projectId", it.toHexString()) }
        from?.let { put("from", it) }
        to?.let { put("to", it) }
        subject?.let { put("subject", it) }
        timestamp?.let { put("timestamp", it) }
        sourceUri?.let { put("sourceUri", it) }
        fileName?.let { put("fileName", it) }
        confluencePageId?.let { put("confluencePageId", it) }
        confluenceSpaceKey?.let { put("confluenceSpaceKey", it) }
        chunkId?.let { put("chunkId", it) }
        chunkOf?.let { put("chunkOf", it) }
        parentRef?.let { put("parentRef", it) }
    }

/**
 * Parse GraphQL response to SearchResult list
 */
@Suppress("UNCHECKED_CAST")
fun WeaviateResult<GraphQLResponse<Any>>.parseSearchResults(): List<SearchResult> {
    if (hasErrors()) {
        return emptyList()
    }

    val data = result?.data as? Map<String, Any> ?: return emptyList()
    val get = data["Get"] as? Map<String, Any> ?: return emptyList()

    // Get the first class results (SemanticText or SemanticCode)
    val classResults = get.values.firstOrNull() as? List<Map<String, Any>> ?: return emptyList()

    return classResults.mapNotNull { obj ->
        parseSearchResult(obj)
    }
}

/**
 * Parse single Weaviate object to SearchResult
 */
@Suppress("UNCHECKED_CAST")
private fun parseSearchResult(obj: Map<String, Any>): SearchResult? {
    // Extract text (required)
    val text = obj["text"] as? String ?: return null

    // Extract metadata
    val additional = obj["_additional"] as? Map<String, Any>
    val id = additional?.get("id") as? String ?: return null

    // Extract score (from distance or score field)
    val score =
        when {
            additional.containsKey("score") -> {
                val scoreRaw = additional["score"]
                when (scoreRaw) {
                    is Number -> scoreRaw.toDouble()
                    is String -> scoreRaw.toDoubleOrNull() ?: 0.0
                    else -> 0.0
                }
            }

            additional.containsKey("distance") -> {
                val distanceRaw = additional["distance"]
                val distance = when (distanceRaw) {
                    is Number -> distanceRaw.toDouble()
                    is String -> distanceRaw.toDoubleOrNull() ?: 0.0
                    else -> 0.0
                }
                1.0 - distance // Convert distance to similarity score
            }

            else -> 0.0
        }

    // Build metadata map (exclude internal fields)
    val metadata =
        obj
            .filterKeys { it != "_additional" && it != "text" }
            .mapValues { (_, value) -> value ?: "" }

    return SearchResult(
        id = id,
        text = text,
        score = score,
        metadata = metadata,
    )
}

/**
 * Extract error messages from Weaviate result
 */
fun <T> WeaviateResult<T>.errorMessages(): String =
    if (hasErrors()) {
        error.messages.joinToString("; ")
    } else {
        "Unknown error"
    }
