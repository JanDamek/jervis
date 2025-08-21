package com.jervis.service.gateway

import com.jervis.domain.model.ModelType

/**
 * Gateway for embedding calls. Uses models order from application properties.
 */
fun interface EmbeddingGateway {
    suspend fun callEmbedding(
        type: ModelType,
        text: String,
    ): List<Float>
}
