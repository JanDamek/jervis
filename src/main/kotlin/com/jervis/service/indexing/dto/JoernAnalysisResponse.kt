package com.jervis.service.indexing.dto

import kotlinx.serialization.Serializable

/**
 * Response data class for EXTENSIVE_JOERN_ANALYSIS LLM prompt.
 * Represents a JSON object containing the comprehensive analysis text from LLM.
 */
@Serializable
data class JoernAnalysisResponse(
    val response: String
)