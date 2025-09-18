package com.jervis.service.gateway.processing

import kotlinx.serialization.Serializable

/**
 * Unified JSON response wrapper for all LLM string responses.
 * Provides a consistent structure for LLM outputs that previously used empty strings.
 */
@Serializable
data class LlmResponseWrapper(
    val response: String = "",
)
