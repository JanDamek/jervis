package com.jervis.service.indexing.domain

import kotlinx.serialization.Serializable

/**
 * Response DTO for content sentence splitting using LLM.
 * Used to convert any input (code, text, meeting) into atomic sentences for RAG storage.
 */
@Serializable
data class ContentSentenceSplittingResponse(
    val sentences: List<String> = emptyList(),
)
