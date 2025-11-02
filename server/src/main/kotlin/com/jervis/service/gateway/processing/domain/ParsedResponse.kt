package com.jervis.service.gateway.processing.domain

/**
 * Data class to hold both think content and parsed JSON result
 */
data class ParsedResponse<T>(
    val thinkContent: String?,
    val result: T,
)
