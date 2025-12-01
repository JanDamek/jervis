package com.jervis.service.gateway.clients.llm

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
import mu.KotlinLogging
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.awaitBody
import java.time.Duration

/**
 * Periodically refreshes keep-alive for LLM models configured in ModelsProperties
 * so that they stay warm in memory. Refresh interval derives from keepAlive value with a safety factor.
 */
@Component
class LlmModelRefresher(
    private val models: ModelsProperties,
    private val webClientFactory: WebClientFactory,
    private val preloadProps: PreloadOllamaProperties,
) {
    private val logger = KotlinLogging.logger {}
    private val scope = CoroutineScope(Dispatchers.IO)
    private var job: Job? = null

    init {
        // Start background refresher
        job =
            scope.launch {
                val llmKeepAlive = preloadProps.llm.keepAlive
                val refreshSafetyFactor = preloadProps.llm.refreshSafetyFactor
                val keepAliveDuration = parseKeepAlive(llmKeepAlive)
                val delayMillis = (keepAliveDuration.toMillis() * refreshSafetyFactor).toLong().coerceAtLeast(30_000L)

                logger.info { "LlmModelRefresher started: keepAlive=$llmKeepAlive, refresh every=${Duration.ofMillis(delayMillis)}" }

                val clients =
                    listOf(
                        "ollama.qualifier" to webClientFactory.getWebClient("ollama.qualifier"),
                        "ollama.primary" to webClientFactory.getWebClient("ollama.primary"),
                    )

                // Build list of models to refresh
                // Consider all model types except embeddings (handled by EmbeddingModelRefresher)
                val nonEmbeddingTypes = ModelTypeEnum.entries.filterNot { it.name.startsWith("EMBEDDING") }

                // Models on GPU (primary)
                val primaryModels: Set<String> =
                    nonEmbeddingTypes
                        .asSequence()
                        .flatMap { type -> models.models[type].orEmpty().asSequence() }
                        .filter { it.provider == ModelProviderEnum.OLLAMA && it.model.isNotBlank() }
                        .map { it.model }
                        .toSet()

                // Models on CPU (qualifier)
                val qualifierModels: Set<String> =
                    nonEmbeddingTypes
                        .asSequence()
                        .flatMap { type -> models.models[type].orEmpty().asSequence() }
                        .filter { it.provider == ModelProviderEnum.OLLAMA_QUALIFIER && it.model.isNotBlank() }
                        .map { it.model }
                        .toSet()

                if (primaryModels.isEmpty() && qualifierModels.isEmpty()) {
                    logger.info { "LlmModelRefresher: no models to refresh (OLLAMA)." }
                    return@launch
                }

                while (isActive) {
                    try {
                        logger.info {
                            "LlmModelRefresher: refreshing keep-alive for ${primaryModels.size + qualifierModels.size} LLM model(s)"
                        }

                        // Refresh GPU (primary) models
                        if (primaryModels.isNotEmpty()) {
                            val primaryClient = clients.first { it.first == "ollama.primary" }.second
                            for (model in primaryModels) {
                                runCatching {
                                    val warmupBody =
                                        mapOf(
                                            "model" to model,
                                            "prompt" to "warmup",
                                            "stream" to false,
                                            "keep_alive" to llmKeepAlive,
                                        )
                                    primaryClient
                                        .post()
                                        .uri("/api/generate")
                                        .bodyValue(warmupBody)
                                        .retrieve()
                                        .awaitBody<Map<String, Any>>()
                                    logger.info { "Refreshed keep-alive on ollama.primary for LLM model: $model ($llmKeepAlive)" }
                                }.onFailure { e ->
                                    logger.warn(e) { "Failed to refresh keep-alive on ollama.primary for model: $model" }
                                }
                            }
                        }

                        // Refresh CPU (qualifier) models
                        if (qualifierModels.isNotEmpty()) {
                            val qualifierClient = clients.first { it.first == "ollama.qualifier" }.second
                            for (model in qualifierModels) {
                                runCatching {
                                    val warmupBody =
                                        mapOf(
                                            "model" to model,
                                            "prompt" to "warmup",
                                            "stream" to false,
                                            "keep_alive" to llmKeepAlive,
                                        )
                                    qualifierClient
                                        .post()
                                        .uri("/api/generate")
                                        .bodyValue(warmupBody)
                                        .retrieve()
                                        .awaitBody<Map<String, Any>>()
                                    logger.info { "Refreshed keep-alive on ollama.qualifier for LLM model: $model ($llmKeepAlive)" }
                                }.onFailure { e ->
                                    logger.warn(e) { "Failed to refresh keep-alive on ollama.qualifier for model: $model" }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        logger.warn(e) { "LlmModelRefresher cycle failed" }
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
}
