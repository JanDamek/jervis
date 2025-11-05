package com.jervis.domain.confluence

/**
 * Domain model for Confluence space.
 * Represents a workspace/space within Confluence.
 */
data class ConfluenceSpace(
    val id: String,
    val key: String,
    val name: String,
    val type: String,
    val status: String,
    val links: Map<String, String>,
)
