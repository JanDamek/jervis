package com.jervis.service.gateway.processing.dto

import kotlinx.serialization.Serializable

/**
 * Unified JSON response wrapper for all LLM string responses.
 * Provides a consistent structure for LLM outputs that previously used empty strings.
 */
@Serializable
data class LlmResponseWrapper(
    val response: String =
        "< Use this JSON format strictly as a wrapper. Only write plain text inside the 'response' " +
            "field. Do not include any JSON, lists, markdown, code, or structured content inside it. " +
            "The outer JSON is just a container for simple human-readable text. >",
)
