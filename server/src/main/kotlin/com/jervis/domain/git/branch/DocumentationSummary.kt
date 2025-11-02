package com.jervis.domain.git.branch

/** Documentation overview. */
data class DocumentationSummary(
    val docsChanged: Boolean,
    val docsFiles: List<String>,
)
