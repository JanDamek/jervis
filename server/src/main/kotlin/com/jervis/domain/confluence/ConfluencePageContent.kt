package com.jervis.domain.confluence

/**
 * Domain model for Confluence page with full content.
 * Used when retrieving page body for indexing.
 */
data class ConfluencePageContent(
    val id: String,
    val status: String,
    val title: String,
    val spaceId: String,
    val parentId: String?,
    val version: PageVersion,
    val bodyHtml: String?, // XHTML storage format
    val links: Map<String, String>,
)
