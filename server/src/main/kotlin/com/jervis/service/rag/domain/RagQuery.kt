package com.jervis.service.rag.domain

import kotlinx.serialization.Serializable

/**
 * Represents a single RAG query with parameters.
 */
@Serializable
data class RagQuery(
    val searchTerms: String = "",
    val filterByProject: Boolean = false,
)
