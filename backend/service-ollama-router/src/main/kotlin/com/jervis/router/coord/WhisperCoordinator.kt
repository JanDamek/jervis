package com.jervis.router.coord

import com.jervis.router.config.ModelCatalog
import com.jervis.router.gpu.GpuPool
import com.jervis.router.gpu.ModelLoader
import com.jervis.router.model.PreemptReason
import com.jervis.router.model.RequestState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import mu.KotlinLogging
import java.time.Instant
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private val logger = KotlinLogging.logger {}

/**
 * Coordinates Whisper transcription preempting on the shared p40-2 GPU.
 *
 * Whisper REST service on the GPU VM calls `WhisperNotify` when it wants
 * to transcribe — router cancels every in-flight LLM/VLM request, unloads
 * those models from VRAM (XTTS + embeddings stay), and waits for Ollama
 * to go quiet (bounded). Once Whisper finishes it calls `WhisperDone` and
 * the dispatcher resumes; preempted requests retry from the gRPC layer.
 *
 * Mirrors `OllamaRouter.notify_whisper_wants_gpu` / `notify_whisper_done`
 * with the stale-safety auto-reset (2h max hold).
 */
class WhisperCoordinator(
    private val gpuPool: GpuPool,
    private val modelLoader: ModelLoader,
    maxHoldS: Long,
) {
    private val maxHoldMs: Long = maxHoldS * 1000L
    private val stateMutex = Mutex()

    @Volatile private var active: Boolean = false
    @Volatile private var activeSince: Instant? = null

    /**
     * Reset on every `WhisperDone`. Awaited by dispatchers that need to wait
     * for VRAM to be free before scheduling new LLM/VLM work.
     */
    @Volatile private var doneSignal: CompletableDeferred<Unit> = CompletableDeferred()

    /**
     * Listener fired when whisper releases — dispatcher uses this to kick
     * its 3 group loops (preempted requests are re-queued, waiting for slot).
     */
    var onDone: (() -> Unit)? = null

    init {
        // Start with whisper *not* active — done signal is "already complete"
        // so any waiter returns immediately.
        doneSignal.complete(Unit)
    }

    suspend fun isBusy(): Boolean {
        if (!active) return false
        val since = activeSince ?: return false
        if (Instant.now().toEpochMilli() - since.toEpochMilli() > maxHoldMs) {
            logger.warn { "WHISPER_STALE: active for >${maxHoldMs / 1000}s, auto-resetting" }
            stateMutex.withLock {
                active = false
                activeSince = null
                if (!doneSignal.isCompleted) doneSignal.complete(Unit)
            }
            return false
        }
        return true
    }

    /**
     * Suspend until whisper is no longer holding the GPU, or [timeoutMs]
     * elapses. Returns true if free, false on timeout.
     */
    suspend fun waitForDone(timeoutMs: Long): Boolean {
        if (!isBusy()) return true
        val signal = doneSignal
        logger.info { "WHISPER_WAIT: waiting for whisper-done (timeout=${timeoutMs}ms)" }
        val outcome = withTimeoutOrNull(timeoutMs.milliseconds) { signal.await() }
        return if (outcome == null) {
            logger.warn { "WHISPER_WAIT: timeout after ${timeoutMs}ms" }
            false
        } else {
            logger.info { "WHISPER_WAIT: whisper done, GPU available" }
            true
        }
    }

    /**
     * Whisper signals it wants the GPU. Returns (granted, preemptedCount,
     * unloadedModels). granted=false ⇒ timed out before quiet.
     *
     * Mirrors `notify_whisper_wants_gpu` semantics:
     *  - Cancel every in-flight non-embedding request (mark PREEMPTED_BY_WHISPER).
     *  - Unload non-embedding models in parallel.
     *  - Bounded wait until no non-embedding request remains.
     */
    suspend fun notifyWantsGpu(preemptTimeoutS: Int): Triple<Boolean, Int, Int> = coroutineScope {
        stateMutex.withLock {
            active = true
            activeSince = Instant.now()
            if (doneSignal.isCompleted) doneSignal = CompletableDeferred()
        }

        var preemptedCount = 0
        for (backend in gpuPool.healthy) {
            backend.mutex.withLock {
                for ((id, req) in backend.activeRequests.toMap()) {
                    if (req.model in ModelCatalog.embeddingModels) continue
                    val current = req.state.get()
                    if (current is RequestState.Preempted) continue
                    logger.warn { "WHISPER_PREEMPT: cancel id=$id model=${req.model} gpu=${backend.name}" }
                    req.state.set(RequestState.Preempted(PreemptReason.WHISPER, emittedChunks = 0))
                    req.cancelToken.cancel()
                    preemptedCount += 1
                }
            }
        }

        val unloadDeadlineMs = (preemptTimeoutS - 2).coerceAtLeast(5) * 1000L
        var unloadedCount = 0
        val unloadJobs = gpuPool.healthy.flatMap { backend ->
            backend.loadedModels.keys
                .filter { it !in ModelCatalog.embeddingModels }
                .toList()
                .map { model ->
                    launch {
                        if (modelLoader.unloadModel(backend, model)) unloadedCount += 1
                    }
                }
        }
        withTimeoutOrNull(unloadDeadlineMs.milliseconds) { unloadJobs.forEach { it.join() } }
        unloadJobs.forEach { if (it.isActive) it.cancel() }

        val deadline = Instant.now().plusMillis(preemptTimeoutS * 1000L)
        while (Instant.now().isBefore(deadline)) {
            val stillBusy = gpuPool.healthy.any { backend ->
                backend.activeRequests.values.any { it.model !in ModelCatalog.embeddingModels }
            }
            if (!stillBusy) break
            delay(200)
        }
        val granted = gpuPool.healthy.none { backend ->
            backend.activeRequests.values.any { it.model !in ModelCatalog.embeddingModels }
        }
        logger.info { "WHISPER_NOTIFY: granted=$granted preempted=$preemptedCount unloaded=$unloadedCount" }
        Triple(granted, preemptedCount, unloadedCount)
    }

    suspend fun notifyDone() {
        stateMutex.withLock {
            active = false
            activeSince = null
            if (!doneSignal.isCompleted) doneSignal.complete(Unit)
        }
        logger.info { "WHISPER_DONE: flag cleared, dispatcher resumed" }
        onDone?.invoke()
    }
}
