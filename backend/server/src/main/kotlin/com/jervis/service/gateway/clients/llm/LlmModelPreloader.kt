package com.jervis.service.gateway.clients.llm

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.jervis.configuration.KtorClientFactory
import com.jervis.configuration.properties.ModelsProperties
import com.jervis.configuration.properties.PreloadOllamaProperties
import com.jervis.domain.model.ModelProviderEnum
import com.jervis.domain.model.ModelTypeEnum
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import mu.KotlinLogging
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.stereotype.Component

/**
 * Preloads LLM models to Ollama instances at application startup.
 *
 * Rationale:
 * - GPU (ollama.primary) must fit models into VRAM – we make sure required models are downloaded
 *   so first call doesn't block on pull.
 * - CPU (ollama.qualifier) má dost RAM – přednahráváme pouze modely označené poskytovatelem
 *   OLLAMA_QUALIFIER (tj. modely pro kvalifikátory). Těžké modely (např. qwen3:30b) na CPU
 *   nestahujeme.
 *
 * Non‑blocking: runs in background and never fails the application startup.
 */
@Component
class LlmModelPreloader(
    private val models: ModelsProperties,
    private val ktorClientFactory: KtorClientFactory,
    private val preloadProps: PreloadOllamaProperties,
) : ApplicationRunner {
    private val logger = KotlinLogging.logger {}
    private val scope = CoroutineScope(Dispatchers.IO)

    override fun run(args: ApplicationArguments) {
        scope.launch {
            try {
                // Consider all model types except embeddings (handled by EmbeddingModelPreloader)
                val nonEmbeddingTypes =
                    ModelTypeEnum.entries.filterNot { it.name.startsWith("EMBEDDING") }

                val allCandidates =
                    nonEmbeddingTypes
                        .asSequence()
                        .flatMap { type -> models.models[type].orEmpty().asSequence() }
                        .filter { it.model.isNotBlank() }

                // Only warm the Workflow agent model on the GPU Ollama (primary)
                // We intentionally ignore all other OLLAMA models to save VRAM and startup time.
                val primaryModels: Set<String> =
                    setOf(
                        "qwen3-coder-tool:30b",
                    )

                val qualifierModels: Set<String> =
                    allCandidates
                        .filter { it.provider == ModelProviderEnum.OLLAMA_QUALIFIER }
                        .map { it.model }
                        .toSet()

                // Pull on GPU (primary) Ollama in parallel (bounded)
                var gpuPulled = 0
                var gpuWarmed = 0
                var cpuPulled = 0
                var cpuWarmed = 0

                val primaryClient = ktorClientFactory.getHttpClient("ollama.primary")
                @Suppress("ktlint:standard:max-line-length")
                logger.info {
                    "LlmModelPreloader: warming only Workflow model on GPU with -> ${primaryModels.joinToString()}"
                }
                val tasks =
                    primaryModels.map { model ->
                        scope.async {
                            try {
                                logger.info { "Preloading Ollama LLM model on GPU instance: $model" }
                                val body = OllamaPullRequest(name = model)
                                val response: HttpResponse =
                                    primaryClient.post("/api/pull") {
                                        setBody(body)
                                    }
                                val status = readPullResponse(response)
                                logger.info { "Ollama pull (GPU) completed for $model: $status" }
                                gpuPulled += 1

                                // Warm-up model in memory with keep_alive
                                try {
                                    val warmupBody =
                                        OllamaGenerateRequest(
                                            model = model,
                                            prompt = "warmup",
                                            stream = false,
                                            keep_alive = preloadProps.llm.keepAlive,
                                        )
                                    primaryClient
                                        .post("/api/generate") {
                                            setBody(warmupBody)
                                        }.body<OllamaGenerateResponse>()
                                    logger.info { "LLM model warmed and kept alive (${preloadProps.llm.keepAlive}) on GPU: $model" }
                                    gpuWarmed += 1
                                } catch (e: Exception) {
                                    logger.warn(e) { "Failed to warm LLM model '$model' on GPU instance" }
                                }
                            } catch (e: Exception) {
                                logger.warn(e) { "Failed to pull Ollama model '$model' on GPU instance" }
                            }
                        }
                    }
                tasks.awaitAll()

                // Pull on CPU (qualifier) Ollama in parallel (bounded) – only OLLAMA_QUALIFIER models
                if (qualifierModels.isNotEmpty()) {
                    val qualifierClient = ktorClientFactory.getHttpClient("ollama.qualifier")
                    logger.info {
                        "LlmModelPreloader: parallel pull on CPU for ${qualifierModels.size} model(s)"
                    }
                    val tasks =
                        qualifierModels.map { model ->
                            scope.async {
                                try {
                                    logger.info { "Preloading Ollama LLM model on CPU instance: $model" }
                                    val body = OllamaPullRequest(name = model)
                                    val response: HttpResponse =
                                        qualifierClient.post("/api/pull") {
                                            setBody(body)
                                        }
                                    val status = readPullResponse(response)
                                    logger.info { "Ollama pull (CPU) completed for $model: $status" }
                                    cpuPulled += 1

                                    // Warm-up model in memory with keep_alive
                                    try {
                                        val warmupBody =
                                            OllamaGenerateRequest(
                                                model = model,
                                                prompt = "warmup",
                                                stream = false,
                                                keep_alive = preloadProps.llm.keepAlive,
                                            )
                                        qualifierClient
                                            .post("/api/generate") {
                                                setBody(warmupBody)
                                            }.body<OllamaGenerateResponse>()
                                        logger.info { "LLM model warmed and kept alive (${preloadProps.llm.keepAlive}) on CPU: $model" }
                                        cpuWarmed += 1
                                    } catch (e: Exception) {
                                        logger.warn(e) { "Failed to warm LLM model '$model' on CPU instance" }
                                    }
                                } catch (e: Exception) {
                                    logger.warn(e) { "Failed to pull Ollama model '$model' on CPU instance" }
                                }
                            }
                        }
                    tasks.awaitAll()
                }

                logger.info {
                    "LlmModelPreloader finished: GPU pulled=$gpuPulled, GPU warmed=$gpuWarmed; CPU pulled=$cpuPulled, CPU warmed=$cpuWarmed; totalGPU=${primaryModels.size}, totalCPU=${qualifierModels.size}"
                }
            } catch (e: Exception) {
                logger.warn(e) { "LlmModelPreloader encountered an error (startup continues)" }
            }
        }
    }

    /**
     * Reads Ollama /api/pull NDJSON streaming response.
     * Returns the final status message.
     */
    private suspend fun readPullResponse(response: HttpResponse): String {
        val channel: ByteReadChannel = response.bodyAsChannel()
        var lastStatus = "unknown"
        val mapper = jacksonObjectMapper()

        while (!channel.isClosedForRead) {
            val line = channel.readUTF8Line() ?: break
            if (line.isNotBlank()) {
                try {
                    val jsonNode = mapper.readTree(line)
                    val status = jsonNode.get("status")?.asText()
                    if (status != null) {
                        lastStatus = status
                    }
                } catch (e: Exception) {
                    logger.warn(e) { "Failed to parse pull response line: $line" }
                }
            }
        }
        return lastStatus
    }

    @Serializable
    data class OllamaPullRequest(
        val name: String,
        val stream: Boolean = false,
    )

    @Serializable
    data class OllamaGenerateRequest(
        val model: String,
        val prompt: String,
        val stream: Boolean,
        val keep_alive: String,
    )

    @Serializable
    data class OllamaGenerateResponse(
        val model: String? = null,
        val response: String? = null,
    )
}
