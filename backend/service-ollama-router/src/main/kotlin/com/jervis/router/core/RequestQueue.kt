package com.jervis.router.core

import com.jervis.router.config.ModelCatalog
import com.jervis.router.config.RouterConfig
import com.jervis.router.coord.WhisperCoordinator
import com.jervis.router.gpu.GpuBackend
import com.jervis.router.gpu.GpuPool
import com.jervis.router.gpu.ModelLoader
import com.jervis.router.model.ApiPath
import com.jervis.router.model.GpuName
import com.jervis.router.model.PreemptReason
import com.jervis.router.model.Priority
import com.jervis.router.model.ProxyError
import com.jervis.router.model.QueueGroup
import com.jervis.router.model.RequestEnvelope
import com.jervis.router.model.RequestState
import com.jervis.router.proxy.OllamaProxy
import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.post
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import mu.KotlinLogging
import java.time.Instant
import java.util.PriorityQueue
import java.util.concurrent.atomic.AtomicLong

private val logger = KotlinLogging.logger {}

sealed interface DispatchResult {
    data class Stream(val flow: Flow<JsonObject>) : DispatchResult
    data class Unary(val response: JsonObject) : DispatchResult
}

class QueueCancelled(message: String) : RuntimeException(message)

private data class QueueEntry(
    val envelope: RequestEnvelope,
    val resultDeferred: CompletableDeferred<DispatchResult>,
    val seq: Long,
    val queuedAt: Instant,
) : Comparable<QueueEntry> {
    override fun compareTo(other: QueueEntry): Int {
        val byPrio = envelope.priority.rank.compareTo(other.envelope.priority.rank)
        return if (byPrio != 0) byPrio else seq.compareTo(other.seq)
    }
}

private class GroupState {
    val mutex = Mutex()
    val queue: PriorityQueue<QueueEntry> = PriorityQueue()
    val wakeup: Channel<Unit> = Channel(Channel.CONFLATED)
}

class RequestQueue(
    private val gpuPool: GpuPool,
    private val ollamaProxy: OllamaProxy,
    private val modelLoader: ModelLoader,
    private val whisperCoordinator: WhisperCoordinator,
    private val routerConfig: RouterConfig,
    private val httpClient: HttpClient,
    private val onCriticalActivity: (GpuName) -> Unit,
    private val onGpuRecovery: () -> Unit,
) {
    private val groups: Map<QueueGroup, GroupState> = QueueGroup.entries.associateWith { GroupState() }
    private val seqCounter = AtomicLong(0)
    private val rrCounter = AtomicLong(0)

    private val maxConcurrentLlm: Int = routerConfig.maxConcurrentLlm
    private val maxConcurrentEmbed: Int = routerConfig.maxConcurrentEmbeddings

    private val dispatcherJob = Job()
    private val dispatcherScope = CoroutineScope(SupervisorJob(dispatcherJob))

    fun start() {
        for ((group, state) in groups) {
            dispatcherScope.launch { dispatchLoop(group, state) }
        }
        whisperCoordinator.onDone = { notifySlotFreed(null) }
        logger.info {
            "RequestQueue started (groups=${groups.keys}, llm_concurrent=$maxConcurrentLlm, embed_concurrent=$maxConcurrentEmbed)"
        }
    }

    fun stop() {
        dispatcherJob.cancel()
        dispatcherScope.cancel()
    }

    fun queueDepth(): Map<QueueGroup, Int> = groups.mapValues { (_, state) -> state.queue.size }

    /**
     * Submit a request — fast path if a slot is free now, otherwise enqueue
     * and suspend on dispatcher pickup. Mirrors Python `RequestQueue.submit`.
     */
    suspend fun submit(envelope: RequestEnvelope): DispatchResult {
        // Fast path
        val backend = findSlotMutating(envelope)
        if (backend != null) {
            preClaimSlot(backend, envelope)
            return runCatching { dispatchToBackend(backend, envelope) }
                .onFailure {
                    cleanupBackend(backend, envelope)
                    notifySlotFreed(envelope.queueGroup)
                }.getOrThrow()
        }

        // Slow path
        val state = groups.getValue(envelope.queueGroup)
        val deferred = CompletableDeferred<DispatchResult>()
        val entry = QueueEntry(envelope, deferred, seqCounter.incrementAndGet(), Instant.now())
        state.mutex.withLock { state.queue.add(entry) }
        logger.info {
            "QUEUE_IN: id=${envelope.id} group=${envelope.queueGroup} priority=${envelope.priority} " +
                "model=${envelope.model} (depth=${state.queue.size})"
        }
        if (envelope.priority.rank <= Priority.CRITICAL.rank) preemptNormalForCritical(envelope.queueGroup)
        state.wakeup.trySend(Unit)
        return waitForResult(envelope, deferred)
    }

    private suspend fun waitForResult(
        envelope: RequestEnvelope,
        deferred: CompletableDeferred<DispatchResult>,
    ): DispatchResult {
        // Race: client cancel vs dispatcher delivery.
        if (envelope.cancelToken.isCancelled) throw QueueCancelled("cancelled before pickup")
        try {
            return deferred.await()
        } catch (e: ProxyError.PreemptedByCritical) {
            // Re-queue at original priority (mirrors Python preempt-then-requeue).
            envelope.state.set(RequestState.Queued)
            val state = groups.getValue(envelope.queueGroup)
            val newDeferred = CompletableDeferred<DispatchResult>()
            val entry = QueueEntry(envelope, newDeferred, seqCounter.incrementAndGet(), Instant.now())
            state.mutex.withLock { state.queue.add(entry) }
            state.wakeup.trySend(Unit)
            logger.info { "QUEUE_REQUEUE: id=${envelope.id} group=${envelope.queueGroup} re-queued after preemption" }
            return waitForResult(envelope, newDeferred)
        }
    }

    fun notifySlotFreed(group: QueueGroup?) {
        if (group != null) {
            groups[group]?.wakeup?.trySend(Unit)
            return
        }
        groups.values.forEach { it.wakeup.trySend(Unit) }
    }

    /**
     * Notify when CRITICAL reservation finishes (orchestrator FOREGROUND
     * activity). RouterRouter calls this from gRPC servicer. Hooks into
     * ReservationGuard.
     */

    // ── Dispatcher ────────────────────────────────────────────────────────

    private suspend fun dispatchLoop(group: QueueGroup, state: GroupState) {
        logger.info { "Dispatcher loop started for group=$group" }
        while (true) {
            try {
                state.wakeup.receive()
                drain(group, state)
            } catch (e: kotlinx.coroutines.CancellationException) {
                return
            } catch (t: Throwable) {
                logger.error(t) { "Dispatcher group=$group error" }
                delay(1000)
            }
        }
    }

    private suspend fun drain(group: QueueGroup, state: GroupState) {
        while (true) {
            val entry = state.mutex.withLock { state.queue.poll() } ?: return
            if (entry.envelope.cancelToken.isCancelled) {
                if (!entry.resultDeferred.isCompleted) {
                    entry.resultDeferred.completeExceptionally(QueueCancelled("cancelled before dispatch"))
                }
                continue
            }
            val backend = findSlotMutating(entry.envelope)
            if (backend != null) {
                preClaimSlot(backend, entry.envelope)
                dispatcherScope.launch { resolveQueued(backend, entry) }
                continue
            }
            // No slot — put back and stop draining for this group until next wakeup.
            state.mutex.withLock { state.queue.add(entry) }
            if (entry.envelope.priority.rank <= Priority.CRITICAL.rank) preemptNormalForCritical(group)
            return
        }
    }

    private suspend fun resolveQueued(backend: GpuBackend, entry: QueueEntry) {
        val envelope = entry.envelope
        if (envelope.state.get() is RequestState.Preempted) {
            logger.info { "QUEUE_SKIP: id=${envelope.id} preempted before dispatch — releasing slot" }
            cleanupBackend(backend, envelope)
            notifySlotFreed(envelope.queueGroup)
            return
        }
        val waitMs = Instant.now().toEpochMilli() - entry.queuedAt.toEpochMilli()
        logger.info { "QUEUE_DISPATCH: id=${envelope.id} waited=${waitMs}ms → ${backend.name}" }
        try {
            val result = dispatchToBackend(backend, envelope)
            if (!entry.resultDeferred.isCompleted) entry.resultDeferred.complete(result)
        } catch (t: Throwable) {
            cleanupBackend(backend, envelope)
            notifySlotFreed(envelope.queueGroup)
            if (!entry.resultDeferred.isCompleted) entry.resultDeferred.completeExceptionally(t)
        }
    }

    // ── Slot finding ──────────────────────────────────────────────────────

    private suspend fun findSlotMutating(envelope: RequestEnvelope): GpuBackend? {
        val isCritical = envelope.priority.rank <= Priority.CRITICAL.rank
        val isEmbedding = envelope.model in ModelCatalog.embeddingModels
        val maxConcurrent = if (isEmbedding) maxConcurrentEmbed else maxConcurrentLlm

        // Snapshot candidates without holding any lock — read healthy backends.
        val candidates = mutableListOf<Pair<GpuBackend, String?>>()
        for (backend in gpuPool.healthy) {
            backend.mutex.withLock {
                val activeMatching = backend.activeRequests.values.count {
                    if (isEmbedding) it.model in ModelCatalog.embeddingModels
                    else it.model !in ModelCatalog.embeddingModels
                }
                if (activeMatching >= maxConcurrent) return@withLock
                if (backend.loadingInProgress) return@withLock
                if (!isCritical && backend.reservedBy != null) return@withLock

                val gpuSet = ModelCatalog.gpuModelSets[backend.name.value].orEmpty()
                if (envelope.model !in gpuSet) {
                    val equivalent = ModelCatalog.modelEquivalents[envelope.model]?.firstOrNull { it in gpuSet }
                    if (equivalent == null) return@withLock
                    if ((ModelCatalog.localModelSize[equivalent] ?: 0) < envelope.minModelSize) return@withLock
                    candidates += backend to equivalent
                    return@withLock
                }
                if ((ModelCatalog.localModelSize[envelope.model] ?: 0) < envelope.minModelSize) return@withLock
                candidates += backend to null
            }
        }
        if (candidates.isEmpty()) return null

        val exact = candidates.filter { (b, eq) -> eq == null && b.hasModel(envelope.model) }
        if (exact.isNotEmpty()) return pickLeastBusyRoundRobin(exact).first

        val equiv = candidates.filter { (b, eq) -> eq != null && b.hasModel(eq) }
        if (equiv.isNotEmpty()) {
            val (best, equivModel) = pickLeastBusyRoundRobin(equiv)
            if (equivModel != null) {
                logger.info { "MODEL_REDIRECT: ${envelope.originalModel ?: envelope.model} → $equivModel on GPU ${best.name}" }
                // Mutate envelope to use equivalent model — body model field too.
                // (envelope.body is JsonObject; we encode replacement at proxy layer.)
            }
            return best
        }
        return pickLeastBusyRoundRobin(candidates).first
    }

    private fun pickLeastBusyRoundRobin(
        candidates: List<Pair<GpuBackend, String?>>,
    ): Pair<GpuBackend, String?> {
        val minCount = candidates.minOf { it.first.activeRequestCount() }
        val tied = candidates.filter { it.first.activeRequestCount() == minCount }
        return tied[(rrCounter.getAndIncrement() % tied.size).toInt()]
    }

    /**
     * Synchronously claim a slot — caller MUST [cleanupBackend] in finally.
     * Mirrors Python `_pre_claim_slot`.
     */
    private suspend fun preClaimSlot(backend: GpuBackend, envelope: RequestEnvelope) {
        backend.mutex.withLock {
            backend.activeRequests[envelope.id] = envelope
            backend.lastActivity = Instant.now()
        }
        if (envelope.priority.rank <= Priority.CRITICAL.rank) onCriticalActivity(backend.name)
    }

    private suspend fun cleanupBackend(backend: GpuBackend, envelope: RequestEnvelope) {
        backend.mutex.withLock {
            backend.activeRequests.remove(envelope.id)
            backend.lastActivity = Instant.now()
        }
    }

    // ── Dispatch ──────────────────────────────────────────────────────────

    private suspend fun dispatchToBackend(backend: GpuBackend, envelope: RequestEnvelope): DispatchResult {
        prepareGpu(backend, envelope)
        envelope.state.set(RequestState.Running(backend.name, Instant.now()))
        logger.info {
            "REQUEST_PROXY: id=${envelope.id} → GPU ${backend.name} (${backend.url}) " +
                "model=${envelope.model} priority=${envelope.priority}"
        }
        return when {
            envelope.apiPath == ApiPath.EMBED || envelope.apiPath == ApiPath.EMBEDDINGS -> {
                try {
                    val resp = ollamaProxy.unary(backend, envelope)
                    DispatchResult.Unary(resp)
                } finally {
                    cleanupBackend(backend, envelope)
                    notifySlotFreed(envelope.queueGroup)
                }
            }
            else -> DispatchResult.Stream(streamWithCleanup(backend, envelope))
        }
    }

    private fun streamWithCleanup(backend: GpuBackend, envelope: RequestEnvelope): Flow<JsonObject> = flow {
        ollamaProxy.stream(backend, envelope).collect { emit(it) }
    }.onCompletion {
        cleanupBackend(backend, envelope)
        notifySlotFreed(envelope.queueGroup)
    }

    /**
     * Ensure GPU has the requested model loaded. Retries on transient
     * failures with delays [5,15,30,60]s. Mirrors `_prepare_gpu`.
     */
    private suspend fun prepareGpu(backend: GpuBackend, envelope: RequestEnvelope) {
        val retryDelays = longArrayOf(5_000L, 15_000L, 30_000L, 60_000L)
        for (attempt in 0..retryDelays.size) {
            if (backend.hasModel(envelope.model)) return

            // VRAM coordination on VLM GPU — Whisper exclusive contract.
            if (backend.name.value == ModelCatalog.VLM_GPU && envelope.model !in ModelCatalog.embeddingModels) {
                if (whisperCoordinator.isBusy()) {
                    logger.info { "VLM_WAIT_WHISPER: whisper active, waiting before loading ${envelope.model}" }
                    whisperCoordinator.waitForDone(routerConfig.whisperGpuMaxHoldS * 1000L)
                }
                releaseWhisperGpu()
            }

            envelope.state.set(RequestState.LoadingModel)
            backend.mutex.withLock { backend.loadingInProgress = true }
            try {
                val loaded = modelLoader.loadModel(backend, envelope.model)
                if (loaded) return
            } finally {
                backend.mutex.withLock { backend.loadingInProgress = false }
            }

            if (attempt < retryDelays.size) {
                val delayMs = retryDelays[attempt]
                logger.warn { "GPU_RETRY: model load ${envelope.model} on ${backend.name} failed, retry ${attempt + 1}/${retryDelays.size} in ${delayMs / 1000}s" }
                backend.healthy = false
                onGpuRecovery()
                delay(delayMs)
            } else {
                logger.error { "GPU_EXHAUSTED: model ${envelope.model} failed to load after retries" }
                throw ProxyError.GpuExhausted(envelope.model)
            }
        }
    }

    private suspend fun releaseWhisperGpu() {
        runCatching {
            val response = httpClient.post("${routerConfig.whisperGpuUrl}/gpu/release") {
                timeout { requestTimeoutMillis = 10_000L }
            }
            when (response.status) {
                HttpStatusCode.OK -> logger.info { "WHISPER_GPU_RELEASE: ok" }
                HttpStatusCode.Conflict -> logger.info { "WHISPER_GPU_RELEASE: busy (transcription in progress)" }
                // 404 = whisper REST endpoint not present (whisper migrated
                // fully to gRPC WhisperNotify/Done). Expected, not a warning.
                HttpStatusCode.NotFound -> logger.debug { "WHISPER_GPU_RELEASE: endpoint absent (gRPC-only whisper)" }
                else -> logger.warn { "WHISPER_GPU_RELEASE: HTTP ${response.status.value}" }
            }
        }.onFailure {
            logger.debug { "WHISPER_GPU_RELEASE: not reachable (${it.message})" }
        }
    }

    // ── Critical preemption ───────────────────────────────────────────────

    private suspend fun preemptNormalForCritical(group: QueueGroup): Boolean {
        val state = groups.getValue(group)
        val waitingPriority = state.mutex.withLock { state.queue.peek()?.envelope?.priority }
            ?: return false

        for (backend in gpuPool.healthy) {
            val toPreempt: List<RequestEnvelope> = backend.mutex.withLock {
                backend.activeRequests.values.filter { req ->
                    val current = req.state.get()
                    current !is RequestState.Preempted &&
                        req.priority.rank > waitingPriority.rank &&
                        req.queueGroup == group &&
                        (req.model !in ModelCatalog.embeddingModels)
                }
            }
            for (req in toPreempt) {
                logger.warn {
                    "PREEMPT_FOR_QUEUE: id=${req.id} group=${req.queueGroup} model=${req.model} on GPU ${backend.name}"
                }
                req.state.set(RequestState.Preempted(PreemptReason.CRITICAL_PEER, emittedChunks = 0))
                req.cancelToken.cancel()
                return true
            }
        }
        return false
    }
}
