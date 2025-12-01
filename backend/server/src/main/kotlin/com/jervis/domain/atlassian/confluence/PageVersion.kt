package com.jervis.domain.atlassian.confluence

import java.time.Instant

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
