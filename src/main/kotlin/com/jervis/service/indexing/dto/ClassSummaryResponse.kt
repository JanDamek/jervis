package com.jervis.service.indexing.dto

import kotlinx.serialization.Serializable

/**
 * Response schema for CLASS_SUMMARY prompt type.
 * Matches the responseSchema defined in prompts.yaml for CLASS_SUMMARY.
 */
@Serializable
data class ClassSummaryResponse(
    val summary: String = ""
)