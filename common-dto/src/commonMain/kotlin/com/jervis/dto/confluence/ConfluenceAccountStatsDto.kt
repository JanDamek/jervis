package com.jervis.dto.confluence

data class ConfluenceAccountStatsDto(
    val totalPages: Long,
    val indexedPages: Long,
    val newPages: Long,
    val failedPages: Long,
    val totalSpaces: Int,
)
