package com.jervis.domain.git

import kotlinx.serialization.Serializable

/**
 * Git workflow configuration for a project.
 * Defines commit conventions, signing requirements, and repository rules.
 */
@Serializable
data class GitConfig(
    val gitUserName: String? = null,
    val gitUserEmail: String? = null,
    val commitMessageTemplate: String? = null,
    val requireGpgSign: Boolean = false,
    val gpgKeyId: String? = null,
    val requireLinearHistory: Boolean = false,
    val conventionalCommits: Boolean = false,
    val commitRules: Map<String, String> = emptyMap(),
)
