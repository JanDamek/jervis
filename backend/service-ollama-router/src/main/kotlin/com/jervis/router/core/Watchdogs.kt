package com.jervis.router.core

import com.jervis.router.config.RouterConfig
import com.jervis.router.coord.ServerCallbackClient
import com.jervis.router.gpu.GpuPool
import com.jervis.router.gpu.ModelLoader
import com.jervis.router.model.GpuName
import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import mu.KotlinLogging
import java.time.Instant
import kotlin.time.Duration.Companion.seconds

private val logger = KotlinLogging.logger {}

class ReservationManager(
    private val gpuPool: GpuPool,
    private val config: RouterConfig,
    private val onSlotFreed: (GpuName) -> Unit,
) {
    private val mutex = Mutex()
    private val reservations: MutableMap<GpuName, String> = HashMap()
    private val reservationTimes: MutableMap<GpuName, Instant> = HashMap()
    private val lastCriticalActivity: MutableMap<GpuName, Instant> = HashMap()

    suspend fun reserve(gpu: GpuName, sessionId: String) {
        mutex.withLock {
            reservations[gpu] = sessionId
            reservationTimes[gpu] = Instant.now()
            lastCriticalActivity[gpu] = Instant.now()
            gpuPool.get(gpu)?.also {
                it.reservedBy = sessionId
                it.reservedAt = Instant.now()
            }
            logger.info { "RESERVE: gpu=$gpu sessionId=$sessionId" }
        }
    }

    suspend fun release(gpu: GpuName) {
        mutex.withLock {
            val sessionId = reservations.remove(gpu)
            reservationTimes.remove(gpu)
            lastCriticalActivity.remove(gpu)
            gpuPool.get(gpu)?.also {
                it.reservedBy = null
                it.reservedAt = null
            }
            if (sessionId != null) logger.info { "RELEASE: gpu=$gpu sessionId=$sessionId" }
        }
        onSlotFreed(gpu)
    }

    suspend fun notifyCriticalActivity(gpu: GpuName) {
        mutex.withLock { lastCriticalActivity[gpu] = Instant.now() }
    }

    suspend fun snapshot(): Map<GpuName, ReservationSnapshot> = mutex.withLock {
        reservations.mapValues { (gpu, session) ->
            ReservationSnapshot(
                sessionId = session,
                reservedAt = reservationTimes[gpu] ?: Instant.now(),
                lastCriticalActivity = lastCriticalActivity[gpu] ?: Instant.now(),
            )
        }
    }

    val isReserved: Boolean get() = reservations.isNotEmpty()
}

data class ReservationSnapshot(
    val sessionId: String,
    val reservedAt: Instant,
    val lastCriticalActivity: Instant,
)

/**
 * Per-GPU reservation watchdog — releases when (a) idle > idleTimeout
 * (no CRITICAL traffic) or (b) total hold > absoluteTimeout (safety).
 * Mirrors Python `_reservation_watchdog`.
 */
class ReservationGuard(
    private val reservationManager: ReservationManager,
    private val config: RouterConfig,
) {
    private var job: Job? = null

    fun start(scope: CoroutineScope) {
        job = scope.launch {
            while (true) {
                try {
                    delay(15.seconds)
                    val now = Instant.now()
                    val snapshot = reservationManager.snapshot()
                    for ((gpu, info) in snapshot) {
                        val idleS = (now.toEpochMilli() - info.lastCriticalActivity.toEpochMilli()) / 1000
                        val totalS = (now.toEpochMilli() - info.reservedAt.toEpochMilli()) / 1000
                        if (idleS >= config.orchestratorIdleTimeoutS) {
                            logger.info { "RESERVATION_IDLE: gpu=$gpu idle=${idleS}s — auto-release" }
                            reservationManager.release(gpu)
                        } else if (totalS >= config.orchestratorReservationTimeoutS) {
                            logger.warn { "RESERVATION_ABSOLUTE: gpu=$gpu held=${totalS}s — force release" }
                            reservationManager.release(gpu)
                        }
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    return@launch
                } catch (t: Throwable) {
                    logger.error(t) { "ReservationGuard error" }
                }
            }
        }
    }

    fun stop() { job?.cancel() }
}

/**
 * Snapshot logger — emits an INFO line every 30 s when there is at least
 * one in-flight request. Mirrors Python `_active_requests_logger`.
 */
class ActiveRequestsLogger(
    private val gpuPool: GpuPool,
) {
    private var job: Job? = null

    fun start(scope: CoroutineScope) {
        job = scope.launch {
            while (true) {
                try {
                    delay(30.seconds)
                    val totalActive = gpuPool.all.sumOf { it.activeRequestCount() }
                    if (totalActive > 0) {
                        val per = gpuPool.all.joinToString(", ") { "${it.name}=${it.activeRequestCount()}" }
                        logger.info { "ACTIVE_REQUESTS: total=$totalActive per_gpu=[$per]" }
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    return@launch
                } catch (t: Throwable) {
                    logger.error(t) { "ActiveRequestsLogger error" }
                }
            }
        }
    }

    fun stop() { job?.cancel() }
}

/**
 * GPU idle notifier — when all GPUs have been idle for > [config.gpuIdleNotifyAfterS],
 * fire one [ServerCallbackClient.gpuIdle] call (debounced; reset on next activity).
 * Mirrors Python `_idle_notify_watchdog`.
 */
class GpuIdleNotifier(
    private val gpuPool: GpuPool,
    private val config: RouterConfig,
    private val serverCallback: ServerCallbackClient,
) {
    private var job: Job? = null
    @Volatile private var lastAnyActivity: Instant = Instant.now()
    @Volatile private var notified: Boolean = false

    fun touch() {
        lastAnyActivity = Instant.now()
        notified = false
    }

    fun start(scope: CoroutineScope) {
        job = scope.launch {
            while (true) {
                try {
                    delay(15.seconds)
                    val totalActive = gpuPool.all.sumOf { it.activeRequestCount() }
                    if (totalActive > 0) {
                        lastAnyActivity = Instant.now()
                        notified = false
                        continue
                    }
                    val idleS = (Instant.now().toEpochMilli() - lastAnyActivity.toEpochMilli()) / 1000
                    if (idleS >= config.gpuIdleNotifyAfterS && !notified) {
                        notified = true
                        logger.info { "GPU_IDLE: all GPUs idle for ${idleS}s — notifying server" }
                        serverCallback.gpuIdle(idleS)
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    return@launch
                } catch (t: Throwable) {
                    logger.error(t) { "GpuIdleNotifier error" }
                }
            }
        }
    }

    fun stop() { job?.cancel() }
}

/**
 * Periodic warmup ping — keeps preloaded models hot in VRAM (well under
 * Ollama's default 5 min auto-evict). Mirrors Python `_warmup_loop`.
 */
class WarmupLoop(
    private val gpuPool: GpuPool,
    private val config: RouterConfig,
    private val httpClient: HttpClient,
) {
    private var job: Job? = null

    fun start(scope: CoroutineScope) {
        if (!config.warmupEnabled) return
        job = scope.launch {
            while (true) {
                try {
                    delay(config.warmupIntervalS.seconds)
                    for (backend in gpuPool.healthy) {
                        for (model in backend.loadedModels.keys.toList()) {
                            runCatching {
                                httpClient.post("${backend.url}/api/show") {
                                    contentType(ContentType.Application.Json)
                                    setBody(
                                        buildJsonObject { put("model", JsonPrimitive(model)) }.toString(),
                                    )
                                    timeout { requestTimeoutMillis = 10_000L }
                                }
                            }.onFailure { logger.debug { "WARMUP: ${backend.name}/$model error: ${it.message}" } }
                        }
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    return@launch
                } catch (t: Throwable) {
                    logger.error(t) { "WarmupLoop error" }
                }
            }
        }
    }

    fun stop() { job?.cancel() }
}
