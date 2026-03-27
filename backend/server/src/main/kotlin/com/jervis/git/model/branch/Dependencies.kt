package com.jervis.git.model.branch

/** Linked artifacts around a branch. */
data class Dependencies(
    val issueKeys: Set<String>,
    val relatedBranches: Set<String>,
    val relatedPrs: Set<String>,
)
