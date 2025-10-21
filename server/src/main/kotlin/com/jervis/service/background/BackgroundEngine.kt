package com.jervis.service.background

import com.jervis.configuration.BackgroundEngineProperties
import com.jervis.domain.background.BackgroundArtifact
import com.jervis.domain.background.BackgroundTask
import com.jervis.domain.background.BackgroundTaskStatus
import com.jervis.domain.background.Checkpoint
import com.jervis.repository.mongo.BackgroundArtifactMongoRepository
import com.jervis.repository.mongo.BackgroundTaskMongoRepository
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import mu.KotlinLogging
import org.bson.types.ObjectId
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

/**
 * Background cognitive engine that runs idle-time LLM tasks.
 *
 * Orchestrates the execution of background tasks when the system is idle,
 * with immediate interruption capability for foreground requests.
 */
@Service
class BackgroundEngine(
    private val llmLoadMonitor: LlmLoadMonitor,
    private val taskRepository: BackgroundTaskMongoRepository,
    private val artifactRepository: BackgroundArtifactMongoRepository,
    private val properties: BackgroundEngineProperties,
    private val taskExecutorRegistry: BackgroundTaskExecutorRegistry,
) {
    private val logger = KotlinLogging.logger {}
    private val supervisor = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Default + supervisor)

    private var engineJob: Job? = null

    @PostConstruct
    fun start() {
        engineJob =
            scope.launch {
                logger.info { "Background engine starting..." }
                runMainLoop()
            }
    }

    @PreDestroy
    fun stop() {
        logger.info { "Background engine stopping..." }
        // Interrupt any running task immediately and cancel the whole scope
        runCatching { interruptNow() }
        engineJob?.cancel()
        supervisor.cancel(CancellationException("Application shutdown"))
        // Best-effort wait for the engine to finish quickly
        try {
            kotlinx.coroutines.runBlocking {
                withTimeout(3000) { engineJob?.join() }
            }
        } catch (_: Exception) {
            // Ignore timeout or cancellation here
        }
    }

    private suspend fun runMainLoop() {
        while (scope.isActive) {
            try {
                val idleThreshold = Duration.ofSeconds(properties.idleThresholdSeconds)

                if (llmLoadMonitor.isIdleFor(idleThreshold)) {
                    val task = findNextPendingOrPartialTask()
                    if (task != null) {
                        executeTask(task)
                    } else {
                        logger.debug { "No pending tasks, sleeping..." }
                        delay(30_000)
                    }
                } else {
                    delay(10_000)
                }
            } catch (e: CancellationException) {
                logger.info { "Background engine cancelled" }
                throw e
            } catch (e: Exception) {
                logger.error(e) { "Error in background engine main loop" }
                delay(60_000)
            }
        }
    }

    private suspend fun findNextPendingOrPartialTask(): BackgroundTask? =
        taskRepository
            .findByStatusInOrderByPriorityAscCreatedAtAsc(
                listOf(
                    BackgroundTaskStatus.PENDING.name,
                    BackgroundTaskStatus.PARTIAL.name,
                ),
            ).firstOrNull()
            ?.toDomain()

    private suspend fun executeTask(task: BackgroundTask) {
        val taskJob =
            scope.launch {
                logger.info { "Starting task: ${task.id} (${task.taskType})" }
                markTaskRunning(task.id)

                try {
                    val executor = taskExecutorRegistry.getExecutor(task.taskType)
                    var currentTask = task

                    while (scope.isActive && llmLoadMonitor.isIdleFor(Duration.ZERO) && currentTask.progress < 1.0) {
                        val chunkResult =
                            withTimeout(properties.chunkTimeoutSeconds * 1000) {
                                executor.executeChunk(currentTask)
                            }

                        saveArtifacts(currentTask.id, chunkResult.artifacts)

                        val newProgress = (currentTask.progress + chunkResult.progressDelta).coerceAtMost(1.0)
                        updateTaskProgress(currentTask.id, chunkResult.checkpoint, newProgress)

                        currentTask = currentTask.copy(checkpoint = chunkResult.checkpoint, progress = newProgress)

                        delay(1500)
                    }

                    if (currentTask.progress >= 1.0) {
                        markTaskCompleted(currentTask.id)
                        logger.info { "Task completed: ${currentTask.id}" }
                    } else {
                        markTaskPartial(currentTask.id)
                        logger.info { "Task partial: ${currentTask.id} (progress=${currentTask.progress})" }
                    }
                } catch (_: CancellationException) {
                    markTaskPartial(task.id)
                    logger.info { "Task interrupted: ${task.id}" }
                } catch (e: Exception) {
                    markTaskFailed(task.id, e.message ?: "unknown")
                    logger.error(e) { "Task failed: ${task.id}" }
                }
            }

        currentTaskJob.set(taskJob)
        taskJob.join()
        currentTaskJob.compareAndSet(taskJob, null)
    }

    private suspend fun markTaskRunning(taskId: ObjectId) {
        val task = taskRepository.findById(taskId) ?: return
        taskRepository.save(
            task.copy(
                status = BackgroundTaskStatus.RUNNING.name,
                updatedAt = Instant.now(),
            ),
        )
    }

    private suspend fun updateTaskProgress(
        taskId: ObjectId,
        checkpoint: Checkpoint?,
        progress: Double,
    ) {
        val task = taskRepository.findById(taskId) ?: return
        taskRepository.save(
            task.copy(
                checkpoint = checkpoint?.let { Json.encodeToString<Checkpoint>(it) },
                progress = progress,
                updatedAt = Instant.now(),
            ),
        )
    }

    private suspend fun markTaskPartial(taskId: ObjectId) {
        val task = taskRepository.findById(taskId) ?: return
        taskRepository.save(
            task.copy(
                status = BackgroundTaskStatus.PARTIAL.name,
                updatedAt = Instant.now(),
            ),
        )
    }

    private suspend fun markTaskCompleted(taskId: ObjectId) {
        val task = taskRepository.findById(taskId) ?: return
        taskRepository.save(
            task.copy(
                status = BackgroundTaskStatus.COMPLETED.name,
                progress = 1.0,
                updatedAt = Instant.now(),
            ),
        )
    }

    private suspend fun markTaskFailed(
        taskId: ObjectId,
        reason: String,
    ) {
        val task = taskRepository.findById(taskId) ?: return
        val newRetryCount = task.retryCount + 1
        val newStatus =
            if (newRetryCount >= task.maxRetries) {
                BackgroundTaskStatus.SUSPENDED
            } else {
                BackgroundTaskStatus.PENDING
            }

        taskRepository.save(
            task.copy(
                status = newStatus.name,
                retryCount = newRetryCount,
                notes = "Failed: $reason",
                updatedAt = Instant.now(),
            ),
        )
    }

    private suspend fun saveArtifacts(
        taskId: ObjectId,
        artifacts: List<BackgroundArtifact>,
    ) {
        artifacts.forEach { artifact ->
            try {
                val exists = artifactRepository.existsByContentHash(artifact.contentHash)
                if (!exists) {
                    artifactRepository.save(
                        com.jervis.entity.mongo.BackgroundArtifactDocument
                            .fromDomain(artifact),
                    )
                    logger.debug { "Saved artifact: ${artifact.id} (${artifact.type})" }
                } else {
                    logger.debug { "Skipped duplicate artifact with hash: ${artifact.contentHash}" }
                }
            } catch (e: Exception) {
                logger.error(e) { "Failed to save artifact: ${artifact.id}" }
            }
        }
    }

    companion object {
        private val currentTaskJob = AtomicReference<Job?>(null)

        /**
         * Immediately interrupts the currently running background task.
         * Called by LlmLoadMonitor when transitioning to busy state.
         */
        fun interruptNow() {
            currentTaskJob.getAndSet(null)?.cancel(CancellationException("Foreground request"))
        }
    }
}
