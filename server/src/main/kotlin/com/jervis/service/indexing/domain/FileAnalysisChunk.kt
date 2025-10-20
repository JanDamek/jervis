package com.jervis.service.indexing.domain

import kotlinx.serialization.Serializable

/**
 * Minimal file-level analysis chunk returned by LLM.
 * Schema strictly follows: { code, descriptionChunks[] }
 */
@Serializable
data class FileAnalysisChunk(
    val code: String = "",
    val descriptionChunks: List<String> = emptyList(),
)
