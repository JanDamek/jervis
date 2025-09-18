package com.jervis.service.gateway.clients

import com.jervis.domain.model.ModelProvider

interface EmbeddingProviderClient {
    val provider: ModelProvider
    
    suspend fun call(model: String, text: String): List<Float>
}