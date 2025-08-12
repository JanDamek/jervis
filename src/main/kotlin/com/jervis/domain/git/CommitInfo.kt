package com.jervis.domain.git

import java.time.Instant

/**
 * Represents information about a Git commit
 */
data class CommitInfo(
    val id: String,
    val authorName: String,
    val authorEmail: String,
    val time: Instant,
    val message: String,
    val changedFiles: List<String> = emptyList(),
)
