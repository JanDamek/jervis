package com.jervis.domain.git.branch

import java.time.Instant

/** Final branch summary used by services (Domain model). */
data class BranchSummary(
    val repoId: RepoId,
    val branch: String,
    val base: String,
    val forkPointSha: String,
    val headSha: String,
    val goal: String,
    val scopeOfChange: ScopeOfChange,
    val dependencies: Dependencies,
    val risks: List<String>,
    val operations: OperationsImpact,
    val testing: TestingSummary,
    val documentation: DocumentationSummary,
    val status: BranchStatusEnum,
    val acceptance: List<String>,
    val generatedAt: Instant = Instant.now(),
)
