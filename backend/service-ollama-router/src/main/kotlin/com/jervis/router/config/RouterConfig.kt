package com.jervis.router.config

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

data class GpuBackendConfig(
    val name: String,
    val url: String,
    val vramGb: Double,
)

@Serializable
private data class GpuBackendJson(
    val name: String,
    val url: String,
    @kotlinx.serialization.SerialName("vram_gb") val vramGb: Double,
)

data class ModelSet(
    val models: List<String>,
    val vramGb: Double,
    val keepAlive: String,
)

data class RouterConfig(
    val grpcPort: Int,
    val healthPort: Int,
    val healthHost: String,
    val orchestratorModel: String,
    val gpuBackends: List<GpuBackendConfig>,
    val orchestratorReservationTimeoutS: Long,
    val orchestratorIdleTimeoutS: Long,
    val modelLoadTimeoutS: Long,
    val backgroundLoadDelayS: Long,
    val proxyConnectTimeoutS: Double,
    val proxyWriteTimeoutS: Double,
    val defaultKeepAlive: String,
    val maxConcurrentLlm: Int,
    val maxConcurrentEmbeddings: Int,
    val warmupEnabled: Boolean,
    val warmupIntervalS: Long,
    val gpuIdleNotifyAfterS: Long,
    val kotlinServerHost: String,
    /**
     * Kotlin server gRPC listener port. The server runs both kRPC (WebSocket
     * over CBOR, default :5500) for UI traffic and gRPC (Netty, default :5501)
     * for pod-to-pod callbacks. Router callbacks (`ServerOpenRouterSettings`
     * etc.) flow over gRPC, so target :5501. Mirrors Python
     * `grpc_server_client._kotlin_server_grpc_target` (`{host}:5501`).
     */
    val kotlinServerGrpcPort: Int,
    val whisperGpuUrl: String,
    val whisperGpuMaxHoldS: Long,
    val clientTierCacheTtlS: Long,
    val mongodbUri: String,
) {
    val vlmGpu: String = "p40-2"

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun load(): RouterConfig {
            val gpuBackendsRaw = System.getenv("GPU_BACKENDS").orEmpty().ifBlank { "[]" }
            val parsed: List<GpuBackendJson> = json.decodeFromString(gpuBackendsRaw)
            val backends = parsed.map { GpuBackendConfig(it.name, it.url.trimEnd('/'), it.vramGb) }
            val kotlinUrl = System.getenv("KOTLIN_SERVER_URL")?.trim().orEmpty()
                .ifBlank { "http://jervis-server:5500" }
            val host = kotlinUrl.substringAfter("://").substringBefore("/").substringBefore(":")
            return RouterConfig(
                grpcPort = System.getenv("ROUTER_GRPC_PORT")?.toIntOrNull() ?: 5501,
                healthPort = System.getenv("HEALTH_PORT")?.toIntOrNull() ?: 8080,
                healthHost = System.getenv("HEALTH_HOST") ?: "0.0.0.0",
                orchestratorModel = System.getenv("ORCHESTRATOR_MODEL") ?: "qwen3-coder-tool:30b",
                gpuBackends = backends,
                orchestratorReservationTimeoutS = envLong("ORCHESTRATOR_RESERVATION_TIMEOUT_S", 600),
                orchestratorIdleTimeoutS = envLong("ORCHESTRATOR_IDLE_TIMEOUT_S", 60),
                modelLoadTimeoutS = envLong("MODEL_LOAD_TIMEOUT_S", 300),
                backgroundLoadDelayS = envLong("BACKGROUND_LOAD_DELAY_S", 5),
                proxyConnectTimeoutS = envDouble("PROXY_CONNECT_TIMEOUT_S", 10.0),
                proxyWriteTimeoutS = envDouble("PROXY_WRITE_TIMEOUT_S", 30.0),
                defaultKeepAlive = System.getenv("DEFAULT_KEEP_ALIVE") ?: "-1",
                maxConcurrentLlm = envInt("MAX_CONCURRENT_LLM", 1),
                maxConcurrentEmbeddings = envInt("MAX_CONCURRENT_EMBEDDINGS", 4),
                warmupEnabled = (System.getenv("WARMUP_ENABLED") ?: "true").toBoolean(),
                warmupIntervalS = envLong("WARMUP_INTERVAL_S", 240),
                gpuIdleNotifyAfterS = envLong("GPU_IDLE_NOTIFY_AFTER_S", 120),
                kotlinServerHost = host.ifBlank { "jervis-server" },
                kotlinServerGrpcPort = envInt("KOTLIN_SERVER_GRPC_PORT", 5501),
                whisperGpuUrl = System.getenv("WHISPER_GPU_URL")
                    ?: "http://ollama.lan.mazlusek.com:8786",
                whisperGpuMaxHoldS = envLong("WHISPER_GPU_MAX_HOLD_S", 7200),
                clientTierCacheTtlS = envLong("CLIENT_TIER_CACHE_TTL_S", 300),
                mongodbUri = System.getenv("MONGODB_URI") ?: "",
            )
        }

        private fun envLong(key: String, default: Long): Long =
            System.getenv(key)?.toLongOrNull() ?: default

        private fun envInt(key: String, default: Int): Int =
            System.getenv(key)?.toIntOrNull() ?: default

        private fun envDouble(key: String, default: Double): Double =
            System.getenv(key)?.toDoubleOrNull() ?: default
    }
}

object ModelCatalog {
    /**
     * VRAM estimates per model (GB). Used by GpuPool for free-VRAM
     * decisions when /api/ps doesn't report `size_vram`.
     * Mirrors `gpu_state.MODEL_VRAM_ESTIMATES`.
     */
    val vramEstimates: Map<String, Double> = mapOf(
        "qwen3-coder-tool:30b" to 18.5,
        "qwen3:14b" to 11.0,
        "qwen3-embedding:8b" to 5.5,
        "qwen3-vl-tool:latest" to 8.8,
    )

    fun estimateVram(model: String): Double = vramEstimates[model] ?: 8.0

    /**
     * Per-GPU model sets. p40-1 stays stable, p40-2 swaps VLM in.
     * Override via env var GPU_MODEL_SETS (JSON map).
     */
    val gpuModelSets: Map<String, List<String>> by lazy {
        System.getenv("GPU_MODEL_SETS")?.takeIf { it.isNotBlank() }?.let {
            runCatching {
                Json.decodeFromString<Map<String, List<String>>>(it)
            }.getOrNull()
        } ?: defaultGpuModelSets
    }

    private val defaultGpuModelSets = mapOf(
        "p40-1" to listOf("qwen3-coder-tool:30b"),
        "p40-2" to listOf("bge-m3", "qwen3:14b", "qwen3-vl-tool:latest"),
    )

    /**
     * Capability → preferred local models (first match wins).
     * Mirrors `models.LOCAL_MODEL_CAPABILITIES`.
     */
    val localModelCapabilities: Map<String, List<String>> = mapOf(
        "qwen3-coder-tool:30b" to listOf("thinking", "coding", "chat", "extraction"),
        "qwen3:14b" to listOf("chat", "extraction", "thinking", "coding"),
        "bge-m3" to listOf("embedding"),
        "qwen3-vl-tool:latest" to listOf("vision", "visual"),
    )

    /**
     * Per-GPU context budget (num_ctx ceiling for the GPU's model set).
     * Mirrors `models.LOCAL_MODEL_CONTEXT`.
     */
    val gpuContextLimits: Map<String, Int> = mapOf(
        "p40-1" to 48_000,
        "p40-2" to 32_000,
    )

    /**
     * Local model size (billions of params) for `min_model_size` filter.
     * 0 means "irrelevant" (embeddings).
     */
    val localModelSize: Map<String, Int> = mapOf(
        "qwen3-coder-tool:30b" to 30,
        "qwen3:14b" to 14,
        "qwen3-vl-tool:latest" to 8,
        "bge-m3" to 0,
    )

    /**
     * Model substitution for queue redirect when the requested model is busy.
     * Mirrors `models.MODEL_EQUIVALENTS`.
     */
    val modelEquivalents: Map<String, List<String>> = mapOf(
        "qwen3:14b" to listOf("qwen3-coder-tool:30b"),
        "qwen3-coder-tool:30b" to listOf("qwen3:14b"),
    )

    val modelSets: Map<String, ModelSet> = mapOf(
        "llm" to ModelSet(listOf("qwen3-coder-tool:30b"), 18.5, "-1"),
        "extraction" to ModelSet(listOf("qwen3:14b"), 11.0, "-1"),
        "embedding" to ModelSet(listOf("bge-m3"), 1.0, "-1"),
        "vlm" to ModelSet(listOf("qwen3-vl-tool:latest"), 8.8, "10m"),
    )

    val modelToSet: Map<String, String> = buildMap {
        modelSets.forEach { (name, def) -> def.models.forEach { put(it, name) } }
    }

    val embeddingModels: Set<String> = setOf("bge-m3")
    val embeddingPaths: Set<String> = setOf("/api/embeddings", "/api/embed")

    const val VLM_GPU: String = "p40-2"
}
