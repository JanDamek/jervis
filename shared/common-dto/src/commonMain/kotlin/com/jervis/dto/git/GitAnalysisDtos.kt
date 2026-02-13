package com.jervis.dto.git

import kotlinx.serialization.Serializable

/**
 * Result of analyzing a git repository for commit patterns and configuration.
 */
@Serializable
data class GitAnalysisResultDto(
    /** Top commiters sorted by commit count (max 10) */
    val topCommitters: List<CommitterInfoDto>,
    /** Detected commit message pattern (with placeholders) */
    val detectedPattern: String?,
    /** Whether GPG signing is used in this repository */
    val usesGpgSigning: Boolean,
    /** GPG key IDs found in commits */
    val gpgKeyIds: List<String> = emptyList(),
    /** Sample commit messages (for user review) */
    val sampleMessages: List<String> = emptyList(),
)

/**
 * Information about a git committer.
 */
@Serializable
data class CommitterInfoDto(
    /** Committer name */
    val name: String,
    /** Committer email */
    val email: String,
    /** Number of commits by this committer */
    val commitCount: Int,
)
