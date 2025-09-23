package com.jervis.service.indexing.dto

import kotlinx.serialization.Serializable

/**
 * Response schema for CONTENT_SENTENCE_SPLITTING prompt type.
 * Matches the responseSchema defined in prompts.yaml for CONTENT_SENTENCE_SPLITTING.
 */
@Serializable
data class ContentSentenceSplittingResponse(
    val sentences: List<String> = emptyList()
)