package com.jervis.service.gateway.clients.embedding

import com.jervis.configuration.WebClientFactory
import com.jervis.configuration.properties.ModelsProperties
import com.jervis.configuration.properties.PreloadOllamaProperties
import com.jervis.domain.model.ModelProviderEnum
import com.jervis.domain.model.ModelTypeEnum
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.springframework.web.reactive.function.client.awaitBody
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
    private val webClientFactory: WebClientFactory,
    private val preloadProps: PreloadOllamaProperties,
) {
    private val logger = KotlinLogging.logger {}
    private val scope = CoroutineScope(Dispatchers.IO)
    private var job: Job? = null

    init {
        // Start background refresher
        job = scope.launch {
            val embedKeepAlive = preloadProps.embed.keepAlive
            val refreshSafetyFactor = preloadProps.embed.refreshSafetyFactor
            val keepAliveDuration = parseKeepAlive(embedKeepAlive)
            val delayMillis = (keepAliveDuration.toMillis() * refreshSafetyFactor).toLong().coerceAtLeast(30_000L)

            logger.info { "EmbeddingModelRefresher started: keepAlive=$embedKeepAlive, refresh every=${Duration.ofMillis(delayMillis)}" }

            val clients = listOf(
                "ollama.qualifier",
                "ollama.primary",
            ).mapNotNull { name ->
                runCatching { name to webClientFactory.getWebClient(name) }.getOrNull()
            }

            // Build list of unique embedding models on OLLAMA
            val modelsToRefresh: List<String> =
                sequenceOf(ModelTypeEnum.EMBEDDING_TEXT, ModelTypeEnum.EMBEDDING_CODE)
                    .flatMap { type -> models.models[type].orEmpty().asSequence() }
                    .filter { it.provider == ModelProviderEnum.OLLAMA && it.model.isNotBlank() }
                    .map { it.model }
                    .distinct()
                    .toList()

            if (modelsToRefresh.isEmpty()) {
                logger.info { "EmbeddingModelRefresher: no models to refresh (OLLAMA)." }
                return@launch
            }

            while (isActive) {
                try {
                    logger.info { "EmbeddingModelRefresher: refreshing keep-alive for ${modelsToRefresh.size} embedding model(s)" }

                    // Simple sequential refresh to avoid overloading local instance (suffices for hourly keep_alive)
                    for (model in modelsToRefresh) {
                        runCatching {
                            val warmupBody = mapOf(
                                "model" to model,
                                // use the same warmup token as preloader to make server do actual compute
                                "input" to "warmup",
                                // some Ollama versions use 'prompt' instead of 'input' for embeddings
                                "prompt" to "warmup",
                                // some Ollama versions accept both top-level and options.keep_alive
                                "keep_alive" to embedKeepAlive,
                                "options" to mapOf("keep_alive" to embedKeepAlive),
                            )
                            clients.forEach { (name, client) ->
                                runCatching {
                                    client
                                        .post()
                                        .uri("/api/embeddings")
                                        .bodyValue(warmupBody)
                                        .retrieve()
                                        .awaitBody<OllamaEmbeddingWarmupResponse>()
                                    logger.info { "Refreshed keep-alive on $name for embedding model: $model ($embedKeepAlive)" }
                                }.onFailure { e ->
                                    logger.warn(e) { "Failed to refresh keep-alive on $name for model: $model" }
                                }
                            }
                        }.onFailure { e ->
                            logger.warn(e) { "Failed to refresh keep-alive for model: $model" }
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
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    data class OllamaEmbeddingWarmupResponse(
        val embedding: List<Double>? = null,
        val embeddings: List<List<Double>>? = null,
    )
}
