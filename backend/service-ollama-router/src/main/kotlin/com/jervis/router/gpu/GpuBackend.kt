package com.jervis.router.gpu

import com.jervis.router.config.ModelCatalog
import com.jervis.router.model.GpuName
import com.jervis.router.model.Priority
import com.jervis.router.model.RequestEnvelope
import com.jervis.router.model.RequestId
import kotlinx.coroutines.sync.Mutex
import java.time.Instant

/**
 * Mutable state of a single GPU Ollama backend. All mutating fields must
 * be accessed under [mutex]. The dispatcher acquires the mutex once per
 * slot operation; long-running requests (model load, stream forward) are
 * NOT held under the mutex — they only enter/leave the active map under it.
 */
class GpuBackend(
    val name: GpuName,
    val url: String,
    val vramGb: Double,
) {
    val mutex: Mutex = Mutex()

    val loadedModels: MutableMap<String, Double> = HashMap()
    val activeRequests: MutableMap<RequestId, RequestEnvelope> = HashMap()

    @Volatile var reservedBy: String? = null
    @Volatile var reservedAt: Instant? = null
    @Volatile var lastActivity: Instant = Instant.now()
    @Volatile var healthy: Boolean = true
    @Volatile var loadingInProgress: Boolean = false

    val usedVramGb: Double get() = loadedModels.values.sum()
    val freeVramGb: Double get() = vramGb - usedVramGb

    fun hasModel(model: String): Boolean = loadedModels.containsKey(model)

    fun activeRequestCount(): Int = activeRequests.size

    fun hasActiveCritical(): Boolean = activeRequests.values.any { it.priority.rank <= Priority.CRITICAL.rank }

    fun hasActiveBackground(): Boolean = activeRequests.values.any { it.priority.rank >= Priority.NORMAL.rank }

    fun currentSet(): String? {
        val loaded = loadedModels.keys
        for ((setName, def) in ModelCatalog.modelSets) {
            if (loaded.intersect(def.models.toSet()).isNotEmpty()) return setName
        }
        return null
    }
}
