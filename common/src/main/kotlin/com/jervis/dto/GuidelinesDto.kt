package com.jervis.dto

import kotlinx.serialization.Serializable

@Serializable
data class GuidelinesDto(
    val codeStyleDocUrl: String? = null,
    val commitMessageConvention: String = "conventional-commits",
    val branchingModel: String = "git-flow",
    val testCoverageTarget: Int = 80,
    val rules: List<String> = emptyList(),
    val patterns: List<String> = emptyList(),
    val conventions: Map<String, String> = emptyMap(),
    val restrictions: List<String> = emptyList(),
)
