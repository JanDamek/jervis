package com.jervis.dto.rag

import kotlinx.serialization.Serializable

@Serializable
data class RagSearchRequestDto(
    val clientId: String,
    val projectId: String? = null,
    val searchText: String,
    val filterKey: String? = null,
    val filterValue: String? = null,
)
