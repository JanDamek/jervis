package com.jervis.dto

import kotlinx.serialization.Serializable

/**
 * DTO for mono-repository configuration.
 * Credentials are not exposed in DTOs for security.
 */
@Serializable
data class MonoRepoConfigDto(
    val id: String,
    val name: String,
    val repositoryUrl: String,
    val defaultBranch: String = "main",
    val hasCredentialsOverride: Boolean = false,
)
