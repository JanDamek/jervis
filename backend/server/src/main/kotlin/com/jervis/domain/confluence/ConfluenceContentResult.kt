package com.jervis.domain.confluence

data class ConfluenceContentResult(
    val indexedChunks: Int,
    val internalLinks: List<String>,
    val externalLinks: List<String>,
    val plainText: String,
)
