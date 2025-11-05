package com.jervis.dto.confluence

import java.time.Instant

/**
 * DTO for Confluence page display in UI.
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
    val lastModifiedAt: Instant? = null,
    val lastIndexedAt: Instant? = null,
    val errorMessage: String? = null,
)
