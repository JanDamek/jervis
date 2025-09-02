package com.jervis.configuration

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component
import java.time.Duration

@Component
@ConfigurationProperties(prefix = "retrys")
class RetrysProperties {
    var embedding: EmbeddingRetrys = EmbeddingRetrys()
    var llm: LlmRetrys = LlmRetrys()
    var qdrant: QdrantRetrys = QdrantRetrys()

    data class EmbeddingRetrys(
        var retryCount: Int = 3,
        var backoffDurationSeconds: Long = 2,
        var maxBackoffDurationSeconds: Long = 30,
        var providers: Map<String, ProviderRetrys> = emptyMap(),
    )

    data class ProviderRetrys(
        var retryCount: Int? = null,
        var backoffDurationSeconds: Long? = null,
        var maxBackoffDurationSeconds: Long? = null,
    )

    data class LlmRetrys(
        var retryCount: Int = 3,
        var backoffDurationSeconds: Long = 1,
        var maxBackoffDurationSeconds: Long = 10,
        var providers: Map<String, ProviderRetrys> = emptyMap(),
    )

    data class QdrantRetrys(
        var connectionRetryCount: Int = 3,
        var connectionBackoffDurationSeconds: Long = 5,
        var operationRetryCount: Int = 2,
        var operationBackoffDurationSeconds: Long = 2,
    )

    // Helper methods for embedding retries
    fun getEmbeddingRetryCount(provider: String): Int {
        val providerRetrys = embedding.providers[provider.lowercase()]
        return providerRetrys?.retryCount ?: embedding.retryCount
    }

    fun getEmbeddingBackoffDuration(provider: String): Duration {
        val providerRetrys = embedding.providers[provider.lowercase()]
        val seconds = providerRetrys?.backoffDurationSeconds ?: embedding.backoffDurationSeconds
        return Duration.ofSeconds(seconds)
    }

    fun getEmbeddingMaxBackoffDuration(provider: String): Duration {
        val providerRetrys = embedding.providers[provider.lowercase()]
        val seconds = providerRetrys?.maxBackoffDurationSeconds ?: embedding.maxBackoffDurationSeconds
        return Duration.ofSeconds(seconds)
    }

    // Helper methods for LLM retries
    fun getLlmRetryCount(provider: String): Int {
        val providerRetrys = llm.providers[provider.lowercase()]
        return providerRetrys?.retryCount ?: llm.retryCount
    }

    fun getLlmBackoffDuration(provider: String): Duration {
        val providerRetrys = llm.providers[provider.lowercase()]
        val seconds = providerRetrys?.backoffDurationSeconds ?: llm.backoffDurationSeconds
        return Duration.ofSeconds(seconds)
    }

    fun getLlmMaxBackoffDuration(provider: String): Duration {
        val providerRetrys = llm.providers[provider.lowercase()]
        val seconds = providerRetrys?.maxBackoffDurationSeconds ?: llm.maxBackoffDurationSeconds
        return Duration.ofSeconds(seconds)
    }
}
