package com.jervis.service.indexing.dto

import kotlinx.serialization.Serializable

/**
 * Response data class for COMPREHENSIVE_FILE_ANALYSIS LLM prompt.
 * Represents a JSON array of short, independently searchable sentences about a source file.
 */
@Serializable
data class ComprehensiveFileAnalysisResponse(
    val sentences: List<String>
)