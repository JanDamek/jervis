package com.jervis.dto

import kotlinx.serialization.Serializable

/**
 * DTOs for listing remote Git branches for a repository.
 */
@Serializable
data class GitBranchListDto(
    val defaultBranch: String? = null,
    val branches: List<String> = emptyList(),
)
