package com.jervis.dto.confluence

/**
 * DTO for updating Confluence account settings.
 */
data class ConfluenceAccountUpdateDto(
    val spaceKeys: List<String>? = null,
    val isActive: Boolean? = null,
)
