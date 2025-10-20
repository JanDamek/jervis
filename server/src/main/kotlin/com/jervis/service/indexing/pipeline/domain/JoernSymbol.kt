package com.jervis.service.indexing.pipeline.domain

import kotlinx.serialization.Serializable

/**
 * Joern symbol representation for pipeline processing (unified with JoernStructuredIndexingService)
 */
@Serializable
data class JoernSymbol(
    val type: JoernSymbolType,
    val name: String,
    val fullName: String,
    val signature: String? = null,
    var filePath: String,
    val lineStart: Int,
    val lineEnd: Int,
    val nodeId: String,
    val parentClass: String? = null,
    var language: String? = null,
    // not from joern JSON, bud added by code
    var code: String? = null,
)
