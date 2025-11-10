package com.jervis.dto.confluence

/**
 * DTO for Confluence page display in UI.
 *
 * Note: Timestamps are ISO-8601 strings for platform compatibility.
 */
data class ConfluencePageDto(
    val id: String,
    val accountId: String,
    val pageId: String,
    val spaceKey: String,
    val title: String,
    val url: String,
    val lastKnownVersion: Int,
    val state: String, // NEW, INDEXED, FAILED
    val parentPageId: String? = null,
    val internalLinksCount: Int,
    val externalLinksCount: Int,
    val childPagesCount: Int,
    val lastModifiedBy: String? = null,
    val lastModifiedAt: String? = null,
    val lastIndexedAt: String? = null,
    val errorMessage: String? = null,
)
