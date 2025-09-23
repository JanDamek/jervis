package com.jervis.service.indexing.dto

import kotlinx.serialization.Serializable

/**
 * Response schema for DOCUMENTATION_PROCESSING prompt type.
 * Matches the responseSchema defined in prompts.yaml for DOCUMENTATION_PROCESSING.
 */
@Serializable
data class DocumentationProcessingResponse(
    val sentences: List<String> = emptyList()
)