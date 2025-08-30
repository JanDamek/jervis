package com.jervis.domain.client

data class Guidelines(
    val codeStyleDocUrl: String? = null,
    val commitMessageConvention: String = "conventional-commits",
    val branchingModel: String = "git-flow",
    val testCoverageTarget: Int = 80,
)
