package com.jervis.domain.git.branch

/** Reference to a git branch including default branch context. */
data class BranchRef(
    val repoId: RepoId,
    val name: String,
    val headSha: String,
    val defaultBranch: String,
)
