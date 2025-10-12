package com.jervis.service.indexing.dto

import kotlinx.serialization.Serializable

/**
 * Response schema for GIT_COMMIT_PROCESSING prompt type.
 * Matches the responseSchema defined in prompts-services.yaml for GIT_COMMIT_PROCESSING.
 */
@Serializable
data class GitCommitProcessingResponse(
    val sentences: List<String> = listOf("describe of commit and changes"),
)
