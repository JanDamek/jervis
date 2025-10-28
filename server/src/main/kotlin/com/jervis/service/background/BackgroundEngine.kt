package com.jervis.service.background

import com.jervis.domain.task.PendingTask
import com.jervis.service.agent.coordinator.AgentOrchestratorService
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
import mu.KotlinLogging
import org.springframework.stereotype.Service
import java.time.Duration
import java.util.concurrent.atomic.AtomicReference

/**
 * Background cognitive engine that processes PendingTasks.
 *
 * TWO INDEPENDENT LOOPS:
 * 1. Qualification loop (CPU) - runs continuously, checks DB every 30s
 * 2. Execution loop (GPU) - processes qualified tasks during idle GPU time
 */
@Service
class BackgroundEngine(
    private val llmLoadMonitor: LlmLoadMonitor,
    private val pendingTaskService: PendingTaskService,
    private val agentOrchestrator: AgentOrchestratorService,
    private val taskQualificationService: TaskQualificationService,
) {
    private val logger = KotlinLogging.logger {}
    private val supervisor = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Default + supervisor)

    private var qualificationJob: Job? = null
    private var executionJob: Job? = null
    private var consecutiveFailures = 0
    private val maxRetryDelay = 300_000L // 5 minutes

    @PostConstruct
    fun start() {
        logger.info { "BackgroundEngine starting - initializing qualification and execution loops..." }

        // Start qualification loop (CPU, independent)
        qualificationJob =
            scope.launch {
                try {
                    logger.info { "Qualification loop STARTED (CPU, independent)" }
                    runQualificationLoop()
                } catch (e: Exception) {
                    logger.error(e) { "Qualification loop FAILED to start!" }
                }
            }

        // Start execution loop (GPU, idle-based)
        executionJob =
            scope.launch {
                try {
                    logger.info { "Execution loop STARTED (GPU, idle-based)" }
                    runExecutionLoop()
                } catch (e: Exception) {
                    logger.error(e) { "Execution loop FAILED to start!" }
                }
            }

        logger.info { "BackgroundEngine initialization complete - both loops launched" }
    }

    @PreDestroy
    fun stop() {
        logger.info { "Background engine stopping..." }
        currentTaskJob.getAndSet(null)?.cancel(CancellationException("Application shutdown"))
        qualificationJob?.cancel()
        executionJob?.cancel()
        supervisor.cancel(CancellationException("Application shutdown"))

        try {
            kotlinx.coroutines.runBlocking {
                withTimeout(3000) {
                    qualificationJob?.join()
                    executionJob?.join()
                }
            }
        } catch (_: Exception) {
            logger.debug { "Background engine shutdown timeout" }
        }
    }

    /**
     * Qualification loop - runs continuously on CPU, independent of GPU state.
     * Processes entire Flow of tasks needing qualification using concurrency limit.
     * Simple: load Flow, process all with semaphore, wait 30s if nothing, repeat.
     */
    private suspend fun runQualificationLoop() {
        logger.info { "Qualification loop entering main loop..." }

        while (scope.isActive) {
            try {
                logger.debug { "Qualification loop: starting processAllQualifications..." }

                // Process all tasks in Flow with concurrency limit (e.g., 8 parallel)
                taskQualificationService.processAllQualifications()

                // If we get here, Flow is exhausted - no more tasks
                logger.info { "Qualification cycle complete - no more tasks, sleeping 30s..." }
                delay(30_000)
            } catch (e: CancellationException) {
                logger.info { "Qualification loop cancelled" }
                throw e
            } catch (e: Exception) {
                logger.error(e) { "ERROR in qualification loop - will retry in 60s" }
                delay(60_000)
            }
        }

        logger.warn { "Qualification loop exited - scope is no longer active" }
    }

    /**
     * Execution loop - processes qualified tasks (needsQualification = false) during idle GPU time.
     * Waits for GPU to be idle before running strong model tasks.
     */
    private suspend fun runExecutionLoop() {
        while (scope.isActive) {
            try {
                val activeRequests = llmLoadMonitor.getActiveRequestCount()
                val idleDuration = llmLoadMonitor.getIdleDuration()

                logger.debug { "Execution loop: activeRequests=$activeRequests, idleDuration=${idleDuration.seconds}s" }

                // Check if GPU is available for strong model tasks
                // If no foreground activity, run background tasks immediately,
                // Idle threshold only applies when there are active foreground requests
                val canRunBackgroundTask =
                    if (activeRequests == 0) {
                        logger.debug { "No active foreground requests, background can run immediately" }
                        true
                    } else {
                        val idleThreshold = Duration.ofSeconds(30)
                        val isIdle = llmLoadMonitor.isIdleFor(idleThreshold)
                        if (isIdle) {
                            logger.debug {
                                "Foreground idle for ${idleDuration.seconds}s (>${idleThreshold.seconds}" +
                                    "s threshold), background can run"
                            }
                        }
                        isIdle
                    }

                if (canRunBackgroundTask) {
                    // Get next task for strong model (needsQualification = false)
                    val task =
                        pendingTaskService
                            .findTasksNeedingQualification(needsQualification = false)
                            .firstOrNull()

                    if (task != null) {
                        logger.info { "Found pending task ${task.id} (${task.taskType}), starting execution..." }
                        executeTask(task) // Blocks until task completes - no other task can start
                        logger.info { "Task execution finished, checking for next task..." }
                        // Loop immediately to check for next task (no delay)
                    } else {
                        logger.debug { "No qualified tasks found, sleeping 30s..." }
                        delay(30_000)
                    }
                } else {
                    logger.debug {
                        "GPU not idle yet (activeRequests=$activeRequests, idle=${idleDuration.seconds}" +
                            "s < 30s), waiting 10s..."
                    }
                    delay(10_000)
                }
            } catch (e: CancellationException) {
                logger.info { "Execution loop cancelled" }
                throw e
            } catch (e: Exception) {
                logger.error(e) { "Error in execution loop" }
                delay(60_000)
            }
        }
    }

    private suspend fun executeTask(task: PendingTask) {
        val taskJob =
            scope.launch {
                logger.info { "Starting pending task: ${task.id} (${task.taskType})" }

                try {
                    // All tasks go through agent orchestrator with goals from YAML
                    agentOrchestrator.handleBackgroundTask(task)

                    pendingTaskService.deleteTask(task.id)
                    logger.info { "Task completed and deleted: ${task.id}" }

                    // Reset failure counter on success
                    consecutiveFailures = 0
                } catch (_: CancellationException) {
                    logger.info { "Task interrupted: ${task.id}" }
                } catch (e: Exception) {
                    consecutiveFailures++
                    val backoffDelay = minOf(30_000L * consecutiveFailures, maxRetryDelay)

                    val errorType =
                        when {
                            e.message?.contains("Connection prematurely closed") == true -> "LLM_CONNECTION_FAILED"
                            e.message?.contains("LLM call failed") == true -> "LLM_UNAVAILABLE"
                            else -> "UNKNOWN_ERROR"
                        }

                    logger.error(e) { "Task failed: ${task.id}, errorType=$errorType, consecutiveFailures=$consecutiveFailures" }
                    pendingTaskService.deleteTask(task.id)
                    logger.warn {
                        "Failed task deleted to prevent retry loop: ${task.id}. Backing off for ${backoffDelay / 1000}" +
                            "s before next attempt."
                    }

                    // Apply backoff delay to prevent rapid failure loops
                    delay(backoffDelay)
                }
            }

        currentTaskJob.set(taskJob)
        taskJob.join()
        currentTaskJob.compareAndSet(taskJob, null)
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
