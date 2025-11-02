package com.jervis.domain.confluence

import java.time.Instant

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

/**
 * Page version information.
 * Version number increments on each edit - used for change detection.
 */
data class PageVersion(
    val number: Int,
    val createdAt: Instant,
    val message: String?,
    val authorId: String?,
)
