package com.jervis.domain.project

import kotlinx.serialization.Serializable

@Serializable
data class IndexingRules(
    val includeGlobs: List<String> = listOf("**/*.kt", "**/*.java", "**/*.md"),
    val excludeGlobs: List<String> = listOf("**/build/**", "**/.git/**", "**/*.min.*"),
    val maxFileSizeMB: Int = 5,
)
