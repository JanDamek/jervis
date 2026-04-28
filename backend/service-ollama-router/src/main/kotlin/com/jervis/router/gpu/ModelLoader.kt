package com.jervis.router.gpu

import com.jervis.router.config.ModelCatalog
import com.jervis.router.config.RouterConfig
import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import mu.KotlinLogging
import java.time.Instant
import kotlin.time.Duration.Companion.seconds

private val logger = KotlinLogging.logger {}

class ModelLoader(
    private val config: RouterConfig,
    private val httpClient: HttpClient,
) {
    /**
     * Load model into VRAM via empty-prompt warmup call.
     *
     * Embedding endpoint (/api/embeddings) needs `input`, generate (/api/generate)
     * needs `prompt` + `stream=false`. Mirrors gpu_state.GpuPool.load_model.
     *
     * Caller must hold [GpuBackend.mutex] OR mark `loadingInProgress=true`
     * to keep slot finder out — long-running call is not lock-held.
     */
    suspend fun loadModel(
        backend: GpuBackend,
        model: String,
        keepAlive: String? = null,
    ): Boolean {
        val effective = keepAlive ?: config.defaultKeepAlive
        val keepAliveValue: Any = effective.toIntOrNull() ?: effective

        val isEmbedding = model in ModelCatalog.embeddingModels
        val endpoint = if (isEmbedding) "/api/embeddings" else "/api/generate"
        val payload = buildJsonObject {
            put("model", JsonPrimitive(model))
            put(
                "keep_alive",
                if (keepAliveValue is Int) JsonPrimitive(keepAliveValue) else JsonPrimitive(keepAliveValue as String),
            )
            if (isEmbedding) {
                put("input", JsonPrimitive(""))
            } else {
                put("prompt", JsonPrimitive(""))
                put("stream", JsonPrimitive(false))
            }
        }

        return runCatching {
            logger.info { "Loading model $model on GPU ${backend.name} (keep_alive=$effective)" }
            val response = httpClient.post("${backend.url}$endpoint") {
                contentType(ContentType.Application.Json)
                setBody(payload.toString())
                timeout { requestTimeoutMillis = config.modelLoadTimeoutS.seconds.inWholeMilliseconds }
            }
            if (!response.status.isSuccess()) {
                val errBody = response.bodyAsText()
                // Load failure is a signal to the dispatcher (retry on another
                // GPU), not a fatal error. Common case: 30b model on a GPU
                // without VRAM headroom — dispatcher falls back to GPU with
                // the model preloaded.
                logger.warn { "Load $model on ${backend.name} failed: HTTP ${response.status} $errBody" }
                return@runCatching false
            }
            backend.loadedModels[model] = ModelCatalog.estimateVram(model)
            backend.lastActivity = Instant.now()
            logger.info { "Model $model loaded on ${backend.name} (${"%.1f".format(backend.usedVramGb)}GB used)" }
            true
        }.getOrElse {
            logger.warn(it) { "Failed to load model $model on GPU ${backend.name}: ${it.message}" }
            false
        }
    }

    suspend fun unloadModel(backend: GpuBackend, model: String): Boolean {
        val isEmbedding = model in ModelCatalog.embeddingModels
        val endpoint = if (isEmbedding) "/api/embeddings" else "/api/generate"
        val payload = buildJsonObject {
            put("model", JsonPrimitive(model))
            put("keep_alive", JsonPrimitive("0"))
            if (isEmbedding) {
                put("input", JsonPrimitive(""))
            } else {
                put("prompt", JsonPrimitive(""))
                put("stream", JsonPrimitive(false))
            }
        }
        return runCatching {
            logger.info { "Unloading model $model from GPU ${backend.name}" }
            val response = httpClient.post("${backend.url}$endpoint") {
                contentType(ContentType.Application.Json)
                setBody(payload.toString())
                timeout { requestTimeoutMillis = 120.seconds.inWholeMilliseconds }
            }
            if (!response.status.isSuccess()) {
                logger.warn { "Unload $model on ${backend.name} HTTP ${response.status}" }
            }
            backend.loadedModels.remove(model)
            true
        }.getOrElse {
            logger.warn(it) { "Failed to unload model $model from GPU ${backend.name}" }
            backend.loadedModels.remove(model)
            false
        }
    }

    /**
     * Wait for active requests to finish (max 60s), then unload all models
     * except those listed in [keep]. Mirrors GpuPool.unload_all.
     */
    suspend fun unloadAll(backend: GpuBackend, keep: Set<String> = emptySet()) {
        val toUnload = backend.loadedModels.keys.toList().filter { it !in keep }
        if (toUnload.isEmpty()) return

        val deadline = Instant.now().plusSeconds(60)
        while (backend.activeRequestCount() > 0 && Instant.now().isBefore(deadline)) {
            logger.info {
                "Waiting for ${backend.activeRequestCount()} active requests on GPU ${backend.name} before unload"
            }
            delay(2.seconds)
        }
        if (backend.activeRequestCount() > 0) {
            logger.warn {
                "GPU ${backend.name} still has ${backend.activeRequestCount()} active requests after 60s — unloading anyway"
            }
        }
        toUnload.forEach { unloadModel(backend, it) }
    }
}

private fun HttpStatusCode.isSuccess(): Boolean = value in 200..299
