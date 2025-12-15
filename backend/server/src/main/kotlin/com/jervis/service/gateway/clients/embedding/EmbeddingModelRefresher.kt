package com.jervis.service.gateway.clients.embedding

import com.jervis.configuration.KtorClientFactory
import com.jervis.configuration.properties.ModelsProperties
import com.jervis.configuration.properties.PreloadOllamaProperties
import com.jervis.domain.model.ModelProviderEnum
import com.jervis.domain.model.ModelTypeEnum
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import mu.KotlinLogging
import org.springframework.stereotype.Component
import java.time.Duration

/**
 * Periodically refreshes keep-alive for embedding models configured in ModelsProperties
 * so that they stay warm in memory. Refresh interval derives from keepAlive value with a safety factor.
 */
@Component
class EmbeddingModelRefresher(
    private val models: ModelsProperties,
    private val ktorClientFactory: KtorClientFactory,
    private val preloadProps: PreloadOllamaProperties,
) {
    private val logger = KotlinLogging.logger {}
    private val scope = CoroutineScope(Dispatchers.IO)
    private var job: Job? = null

    init {
        // Start background refresher
        job =
            scope.launch {
                val embedKeepAlive = preloadProps.embed.keepAlive
                val refreshSafetyFactor = preloadProps.embed.refreshSafetyFactor
                val keepAliveDuration = parseKeepAlive(embedKeepAlive)
                val delayMillis = (keepAliveDuration.toMillis() * refreshSafetyFactor).toLong().coerceAtLeast(30_000L)

                logger.info {
                    "EmbeddingModelRefresher started: keepAlive=$embedKeepAlive, refresh every=${
                        Duration.ofMillis(
                            delayMillis,
                        )
                    }"
                }

                val embeddingClient = ktorClientFactory.getHttpClient("ollama.embedding")

                // Build list of unique embedding models on OLLAMA_EMBEDDING
                val modelsToRefresh: List<String> =
                    sequenceOf(ModelTypeEnum.EMBEDDING)
                        .flatMap { type -> models.models[type].orEmpty().asSequence() }
                        .filter { it.provider == ModelProviderEnum.OLLAMA_EMBEDDING && it.model.isNotBlank() }
                        .map { it.model }
                        .distinct()
                        .toList()

                if (modelsToRefresh.isEmpty()) {
                    logger.info { "EmbeddingModelRefresher: no models to refresh (OLLAMA_EMBEDDING)." }
                    return@launch
                }

                while (isActive) {
                    try {
                        logger.info { "EmbeddingModelRefresher: refreshing keep-alive for ${modelsToRefresh.size} embedding model(s)" }

                        // Simple sequential refresh to avoid overloading local instance (suffices for hourly keep_alive)
                        for (model in modelsToRefresh) {
                            runCatching {
                                val warmupBody =
                                    OllamaEmbeddingRefreshRequest(
                                        model = model,
                                        input = "warmup",
                                        prompt = "warmup",
                                        keep_alive = embedKeepAlive,
                                        options = OllamaEmbeddingOptions(keep_alive = embedKeepAlive),
                                    )
                                embeddingClient
                                    .post("/api/embeddings") {
                                        setBody(warmupBody)
                                    }.body<OllamaEmbeddingWarmupResponse>()
                                logger.info { "Refreshed keep-alive on ollama.embedding for embedding model: $model ($embedKeepAlive)" }
                            }.onFailure { e ->
                                logger.warn(e) { "Failed to refresh keep-alive on ollama.embedding for model: $model" }
                            }
                        }
                    } catch (e: Exception) {
                        logger.warn(e) { "EmbeddingModelRefresher cycle failed" }
                    }

                    delay(delayMillis)
                }
            }
    }

    private fun parseKeepAlive(expr: String): Duration {
        // Supported formats: e.g. "3600s", "60m", "1h"; defaults to 1h on parse error
        return try {
            val trimmed = expr.trim().lowercase()
            when {
                trimmed.endsWith("ms") -> Duration.ofMillis(trimmed.removeSuffix("ms").toLong())
                trimmed.endsWith("s") -> Duration.ofSeconds(trimmed.removeSuffix("s").toLong())
                trimmed.endsWith("m") -> Duration.ofMinutes(trimmed.removeSuffix("m").toLong())
                trimmed.endsWith("h") -> Duration.ofHours(trimmed.removeSuffix("h").toLong())
                else -> Duration.ofHours(1)
            }
        } catch (_: Exception) {
            Duration.ofHours(1)
        }
    }

    @Serializable
    data class OllamaEmbeddingRefreshRequest(
        val model: String,
        val input: String,
        val prompt: String,
        val keep_alive: String,
        val options: OllamaEmbeddingOptions,
    )

    @Serializable
    data class OllamaEmbeddingOptions(
        val keep_alive: String,
    )

    @Serializable
    data class OllamaEmbeddingWarmupResponse(
        val embedding: List<Double>? = null,
        val embeddings: List<List<Double>>? = null,
    )
}
