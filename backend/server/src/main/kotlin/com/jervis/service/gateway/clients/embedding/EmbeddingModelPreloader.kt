package com.jervis.service.gateway.clients.embedding

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.jervis.configuration.WebClientFactory
import com.jervis.configuration.properties.ModelsProperties
import com.jervis.configuration.properties.PreloadOllamaProperties
import com.jervis.domain.model.ModelProviderEnum
import com.jervis.domain.model.ModelTypeEnum
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import mu.KotlinLogging
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.awaitBody

/**
 * Preloads embedding models to the CPU (qualifier) Ollama instance at application startup.
 * This allows LM Studio to be turned off while keeping embeddings available.
 */
@Component
class EmbeddingModelPreloader(
    private val models: ModelsProperties,
    private val webClientFactory: WebClientFactory,
    private val preloadProps: PreloadOllamaProperties,
) : ApplicationRunner {
    private val logger = KotlinLogging.logger {}
    private val scope = CoroutineScope(Dispatchers.IO)

    override fun run(args: ApplicationArguments?) {
        // Run non-blocking preload on startup
        scope.launch {
            try {
                // Collect embedding candidates for dedicated embedding endpoint across TEXT and CODE, de-duplicated by model name
                val candidates:
                    Collection<ModelsProperties.ModelDetail> =
                    sequenceOf(ModelTypeEnum.EMBEDDING_TEXT, ModelTypeEnum.EMBEDDING_CODE)
                        .flatMap { type -> models.models[type].orEmpty().asSequence() }
                        .filter { it.provider == ModelProviderEnum.OLLAMA_EMBEDDING && it.model.isNotBlank() }
                        .groupBy { it.model }
                        .mapValues { (_, list) -> list.first() } // pick first entry for a given model
                        .values

                if (candidates.isEmpty()) {
                    logger.info { "No OLLAMA embedding models configured to preload." }
                    return@launch
                }

                val client = webClientFactory.getWebClient("ollama.embedding")

                val concurrency = preloadProps.embed.concurrency.coerceAtLeast(1)
                logger.info { "EmbeddingModelPreloader: parallel pull on embedding endpoint with concurrency=$concurrency for ${candidates.size} model(s)" }

                val semaphore = Semaphore(concurrency)
                var pulled = 0
                var warmed = 0
                val tasks = candidates.map { detail ->
                    scope.async {
                        semaphore.withPermit {
                            try {
                                val model = detail.model
                                val expectedDim = detail.dimension
                                logger.info { "Preloading Ollama embedding model on CPU instance: $model" }
                                val body = mapOf(
                                    "name" to model,
                                    "stream" to false
                                )
                                val response =
                                    client
                                        .post()
                                        .uri("/api/pull")
                                        .bodyValue(body)
                                        .retrieve()
                                        .awaitBody<OllamaPullResponse>()
                                logger.info { "Ollama pull completed for $model: $response" }
                                pulled += 1

                                // Warm-up embeddings to load into memory and set keep-alive
                                try {
                                    val warmupBody = mapOf(
                                        "model" to model,
                                        "input" to "warmup",
                                        "keep_alive" to preloadProps.embed.keepAlive,
                                    )
                                    val warmupResp =
                                        client
                                            .post()
                                            .uri("/api/embeddings")
                                            .bodyValue(warmupBody)
                                            .retrieve()
                                            .awaitBody<OllamaEmbeddingWarmupResponse>()
                                    val detectedDim = warmupResp.dimension()
                                    if (detectedDim != null) {
                                        if (expectedDim != null && expectedDim != detectedDim) {
                                            logger.warn {
                                                "Embedding dimension mismatch for $model: configured=$expectedDim, detected=$detectedDim. " +
                                                    "Consider updating models-config.yaml."
                                            }
                                        }
                                        logger.info { "Embedding model warmed and kept alive (${preloadProps.embed.keepAlive}): $model (dim=$detectedDim)" }
                                    } else {
                                        logger.info { "Embedding model warmed and kept alive (${preloadProps.embed.keepAlive}): $model (dim=unknown)" }
                                    }
                                    warmed += 1
                                } catch (e: Exception) {
                                    logger.warn(e) { "Failed to warm embedding model '$model' on CPU instance" }
                                }
                            } catch (e: Exception) {
                                logger.warn(e) { "Failed to pull Ollama model '${detail.model}' on CPU instance" }
                            }
                        }
                    }
                }
                tasks.awaitAll()
                logger.info { "EmbeddingModelPreloader finished: pulled=$pulled, warmed=$warmed, total=${candidates.size}" }
            } catch (e: Exception) {
                logger.warn(e) { "EmbeddingModelPreloader encountered an error (startup continues)" }
            }
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class OllamaPullResponse(
        val status: String? = null,
        val success: Boolean? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class OllamaEmbeddingWarmupResponse(
        val embedding: List<Double>? = null,
        val embeddings: List<List<Double>>? = null,
    ) {
        fun dimension(): Int? = when {
            embedding != null && embedding.isNotEmpty() -> embedding.size
            embeddings != null && embeddings.isNotEmpty() -> embeddings.first().size
            else -> null
        }
    }
}
