package com.jervis.service.gateway.clients

import com.jervis.domain.model.ModelProviderEnum

interface EmbeddingProviderClient {
    val provider: ModelProviderEnum

    suspend fun call(
        model: String,
        text: String,
    ): List<Float>
}
