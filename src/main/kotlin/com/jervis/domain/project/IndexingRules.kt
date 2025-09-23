package com.jervis.domain.project

data class IndexingRules(
    val includeGlobs: List<String> = listOf("**/*.kt", "**/*.java", "**/*.md"),
    val excludeGlobs: List<String> = listOf("**/build/**", "**/.git/**", "**/*.min.*"),
    val maxFileSizeMB: Int = 5,
    val usePipelineIndexing: Boolean = false, // Enable pipeline-based indexing for better performance
)
