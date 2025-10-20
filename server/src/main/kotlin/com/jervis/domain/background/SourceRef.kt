package com.jervis.domain.background

import kotlinx.serialization.Serializable

@Serializable
data class SourceRef(
    val type: SourceRefType,
    val id: String,
    val offset: Int? = null,
    val lineStart: Int? = null,
    val lineEnd: Int? = null,
)
