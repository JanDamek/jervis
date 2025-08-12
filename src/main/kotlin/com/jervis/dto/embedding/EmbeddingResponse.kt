package com.jervis.dto.embedding

import com.jervis.dto.Usage

data class EmbeddingResponse(
    val data: List<EmbeddingItem>,
    val model: String,
    val usage: Usage,
)
