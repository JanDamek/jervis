package com.jervis.domain.model

data class ProviderCapabilities(
    val provider: ModelProviderEnum,
    val endpoint: String,
    val maxConcurrentRequests: Int,
)
