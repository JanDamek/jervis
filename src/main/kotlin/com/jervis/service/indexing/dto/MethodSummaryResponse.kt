package com.jervis.service.indexing.dto

import kotlinx.serialization.Serializable

/**
 * Response schema for METHOD_SUMMARY prompt type.
 * Matches the responseSchema defined in prompts.yaml for METHOD_SUMMARY.
 */
@Serializable
data class MethodSummaryResponse(
    val summary: String = ""
)