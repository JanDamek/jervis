package com.jervis.domain.git.branch

/** Aggregated scope of changes for a branch. */
data class ScopeOfChange(
    val modules: List<String>,
    val fileCount: Int,
    val linesAdded: Int,
    val linesDeleted: Int,
    val tagsHistogram: Map<ChangeTagEnum, Int>,
)
