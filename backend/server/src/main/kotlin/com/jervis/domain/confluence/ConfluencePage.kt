package com.jervis.domain.confluence

/**
 * Domain model for Confluence page metadata.
 * Lightweight representation without full content.
 */
data class ConfluencePage(
    val id: String,
    val status: String,
    val title: String,
    val spaceId: String,
    val parentId: String?,
    val version: PageVersion,
    val links: Map<String, String>,
)
