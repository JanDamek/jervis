package com.jervis.dto

import kotlinx.serialization.Serializable

@Serializable
data class IndexingRulesDto(
    val includeGlobs: List<String> = listOf("**/*.kt", "**/*.java", "**/*.md"),
    val excludeGlobs: List<String> = listOf("**/build/**", "**/.git/**", "**/*.min.*"),
    val maxFileSizeMB: Int = 5,
)
