package com.jervis.router.gpu

import com.jervis.router.config.GpuBackendConfig
import com.jervis.router.config.ModelCatalog
import com.jervis.router.config.RouterConfig
import com.jervis.router.model.GpuName
import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.head
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mu.KotlinLogging
import kotlin.time.Duration.Companion.seconds

private val logger = KotlinLogging.logger {}

class GpuPool(
    configs: List<GpuBackendConfig>,
    private val routerConfig: RouterConfig,
    private val httpClient: HttpClient,
) {
    private val backends: Map<GpuName, GpuBackend> = configs.associate { cfg ->
        GpuName(cfg.name) to GpuBackend(GpuName(cfg.name), cfg.url, cfg.vramGb)
    }

    val all: List<GpuBackend> get() = backends.values.toList()
    val healthy: List<GpuBackend> get() = backends.values.filter { it.healthy }

    fun get(name: GpuName): GpuBackend? = backends[name]
    fun get(name: String): GpuBackend? = backends[GpuName(name)]

    fun findWithModel(model: String): GpuBackend? =
        healthy.filter { it.hasModel(model) }
            .minByOrNull { it.activeRequestCount() }

    fun findWithFreeVram(model: String): GpuBackend? {
        val needed = ModelCatalog.estimateVram(model)
        return healthy.filter { it.freeVramGb >= needed }
            .maxByOrNull { it.freeVramGb }
    }

    fun findUnreserved(): GpuBackend? =
        healthy.filter { it.reservedBy == null && !it.loadingInProgress }
            .minByOrNull { it.activeRequestCount() }

    fun findUnreservedWithModel(model: String): GpuBackend? =
        healthy.firstOrNull { it.hasModel(model) && it.reservedBy == null && !it.loadingInProgress }

    fun findUnreservedWithFreeVram(model: String): GpuBackend? {
        val needed = ModelCatalog.estimateVram(model)
        return healthy.filter { it.freeVramGb >= needed && it.reservedBy == null && !it.loadingInProgress }
            .maxByOrNull { it.freeVramGb }
    }

    fun findUnreservedLeastBusy(): GpuBackend? =
        healthy.filter { it.reservedBy == null && !it.loadingInProgress }
            .minByOrNull { it.activeRequestCount() }

    fun findLeastBusy(): GpuBackend? = healthy.minByOrNull { it.activeRequestCount() }

    /**
     * Best GPU for orchestrator reservation: prefer one that already has the
     * orchestrator model, then unreserved, then any healthy candidate.
     * Mirrors GpuPool.find_for_reservation.
     */
    fun findForReservation(exclude: Set<GpuName> = emptySet()): GpuBackend? {
        val candidates = healthy.filter { it.name !in exclude }
        if (candidates.isEmpty()) return null
        val orchestratorModel = routerConfig.orchestratorModel
        candidates.firstOrNull { it.hasModel(orchestratorModel) }?.let { return it }
        val unreserved = candidates.filter { it.reservedBy == null }
        if (unreserved.isNotEmpty()) return unreserved.minByOrNull { it.activeRequestCount() }
        return candidates.minByOrNull { it.activeRequestCount() }
    }

    /**
     * Reconcile loaded model state via /api/ps on each backend.
     * Mirrors GpuPool.sync_state.
     */
    suspend fun syncState() {
        val json = Json { ignoreUnknownKeys = true }
        for (backend in all) {
            runCatching {
                val response = httpClient.get("${backend.url}/api/ps") {
                    timeout { requestTimeoutMillis = 10.seconds.inWholeMilliseconds }
                }
                val raw = response.bodyAsText()
                val data = json.parseToJsonElement(raw).jsonObject
                backend.loadedModels.clear()
                val models = data["models"]?.jsonArray ?: return@runCatching
                models.forEach { entry ->
                    val obj = entry.jsonObject
                    val name = obj["name"]?.jsonPrimitive?.contentOrNullSafe().orEmpty()
                    val sizeVramBytes = obj["size_vram"]?.jsonPrimitive?.contentOrNullSafe()?.toLongOrNull() ?: 0L
                    val vram = if (sizeVramBytes > 0) {
                        sizeVramBytes / 1_073_741_824.0
                    } else {
                        ModelCatalog.estimateVram(name)
                    }
                    if (name.isNotEmpty()) backend.loadedModels[name] = vram
                }
                backend.healthy = true
                logger.info {
                    "GPU ${backend.name} synced: loaded=${backend.loadedModels.keys}, " +
                        "${"%.1f".format(backend.usedVramGb)}GB/${"%.1f".format(backend.vramGb)}GB"
                }
            }.onFailure {
                backend.healthy = false
                logger.warn { "GPU ${backend.name} sync failed: ${it.message}" }
            }
        }
    }

    /**
     * Liveness probe — HEAD on each backend root URL.
     */
    suspend fun checkHealth() {
        for (backend in all) {
            runCatching {
                val response = httpClient.head("${backend.url}/") {
                    timeout { requestTimeoutMillis = 5.seconds.inWholeMilliseconds }
                }
                val wasHealthy = backend.healthy
                backend.healthy = response.status.value == 200
                if (!wasHealthy && backend.healthy) {
                    logger.info { "GPU ${backend.name} recovered" }
                    syncState()
                }
            }.onFailure {
                if (backend.healthy) logger.warn { "GPU ${backend.name} unhealthy: ${it.message}" }
                backend.healthy = false
            }
        }
    }
}

private fun kotlinx.serialization.json.JsonPrimitive.contentOrNullSafe(): String? =
    if (isString) content else content
