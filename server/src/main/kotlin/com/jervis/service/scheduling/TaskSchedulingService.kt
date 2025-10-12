package com.jervis.service.scheduling

import com.jervis.dto.ChatRequestContext
import com.jervis.entity.mongo.ScheduledTaskDocument
import com.jervis.repository.mongo.ProjectMongoRepository
import com.jervis.repository.mongo.ScheduledTaskMongoRepository
import com.jervis.service.agent.coordinator.AgentOrchestratorService
import com.jervis.service.client.ClientService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import org.bson.types.ObjectId
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.Instant

/**
 * Service for managing scheduled tasks and executing them.
 * Handles task creation, execution, retry logic, and cleanup.
 */
@Service
class TaskSchedulingService(
    private val scheduledTaskRepository: ScheduledTaskMongoRepository,
    private val projectRepository: ProjectMongoRepository,
    private val agentOrchestratorService: AgentOrchestratorService,
    private val clientService: ClientService,
    private val taskManagementService: TaskManagementService,
) {
    companion object {
        private val logger = KotlinLogging.logger {}
        private const val STUCK_TASK_TIMEOUT_HOURS = 6L
    }

    /**
     * Schedule a new task
     */
    suspend fun scheduleTask(
        projectId: ObjectId,
        taskInstruction: String,
        taskName: String,
        scheduledAt: Instant,
        taskParameters: Map<String, String> = emptyMap(),
        priority: Int = 0,
        maxRetries: Int = 3,
        cronExpression: String? = null,
        createdBy: String = "system",
    ): ScheduledTaskDocument =
        taskManagementService.scheduleTask(
            projectId = projectId,
            taskInstruction = taskInstruction,
            taskName = taskName,
            scheduledAt = scheduledAt,
            taskParameters = taskParameters,
            priority = priority,
            maxRetries = maxRetries,
            cronExpression = cronExpression,
            createdBy = createdBy,
        )

    /**
     * Execute pending tasks that are ready to run
     */
    @Scheduled(fixedDelay = 60000) // Run every minute
    suspend fun processPendingTasks() {
        try {
            val pendingTasks =
                scheduledTaskRepository
                    .findPendingTasksScheduledBefore(Instant.now())

            pendingTasks.collect { task ->
                try {
                    executeTask(task)
                } catch (e: Exception) {
                    logger.error(e) { "Failed to execute task: ${task.taskName}" }
                    markTaskAsFailed(task, e.message ?: "Unknown error")
                }
            }
        } catch (e: Exception) {
            logger.error(e) { "Error processing pending tasks" }
        }
    }

    /**
     * Execute a specific task
     */
    suspend fun executeTask(task: ScheduledTaskDocument) =
        withContext(Dispatchers.Default) {
            logger.info { "Executing task: ${task.taskName} with instruction: ${task.taskInstruction}" }

            // Mark task as running
            val runningTask =
                taskManagementService.updateTaskStatus(task, ScheduledTaskDocument.ScheduledTaskStatus.RUNNING)

            try {
                val project =
                    projectRepository.findById(task.projectId)
                        ?: throw IllegalStateException("Project not found: ${task.projectId}")

                // Get client for the project
                val clientId =
                    project.clientId
                        ?: throw IllegalStateException("Project ${project.name} has no associated client")

                clientService.getClientById(clientId)
                    ?: throw IllegalStateException("Client not found: $clientId")

                // Create ChatRequestContext for the task
                val chatRequestContext =
                    ChatRequestContext(
                        clientId = clientId,
                        projectId = project.id,
                        quick = task.taskParameters["quick"]?.toBoolean() ?: false,
                        existingContextId = null, // Always create new context for scheduled tasks
                    )

                // Execute task through AgentOrchestratorService using the task instruction
                val response = agentOrchestratorService.handle(task.taskInstruction, chatRequestContext)

                logger.info {
                    "Task completed successfully: ${task.taskName}. " +
                        "Response message length: ${response.message.length} characters"
                }

                // Mark task as completed
                taskManagementService.updateTaskStatus(runningTask, ScheduledTaskDocument.ScheduledTaskStatus.COMPLETED)
            } catch (e: Exception) {
                logger.error(e) { "Task execution failed: ${task.taskName}" }
                markTaskAsFailed(runningTask, e.message ?: "Execution failed")
            }
        }

    private suspend fun markTaskAsFailed(
        task: ScheduledTaskDocument,
        errorMessage: String,
    ) = withContext(Dispatchers.IO) {
        val newRetryCount = task.retryCount + 1
        val failedTask = task.copy(retryCount = newRetryCount)

        // Mark task as failed using TaskManagementService
        taskManagementService.updateTaskStatus(
            failedTask,
            ScheduledTaskDocument.ScheduledTaskStatus.FAILED,
            errorMessage,
        )

        if (newRetryCount < task.maxRetries) {
            // Schedule retry with exponential backoff
            val retryDelay = Duration.ofMinutes(5L * (1L shl (newRetryCount - 1))) // 5, 10, 20 minutes
            taskManagementService.scheduleTask(
                projectId = task.projectId,
                taskInstruction = task.taskInstruction,
                taskName = task.taskName,
                scheduledAt = Instant.now().plus(retryDelay),
                taskParameters = task.taskParameters,
                priority = task.priority,
                maxRetries = task.maxRetries,
                cronExpression = task.cronExpression,
                createdBy = task.createdBy,
            )
            logger.info {
                "Scheduled retry $newRetryCount/${task.maxRetries} for task: ${task.taskName} in ${retryDelay.toMinutes()} minutes"
            }
        } else {
            logger.warn { "Task ${task.taskName} failed after ${task.maxRetries} retries" }
        }
    }

    /**
     * Cleanup stuck tasks (running for too long)
     */
    @Scheduled(fixedDelay = 3600000) // Run every hour
    suspend fun cleanupStuckTasks() {
        try {
            val stuckThreshold = Instant.now().minus(Duration.ofHours(STUCK_TASK_TIMEOUT_HOURS))
            val stuckTasks = scheduledTaskRepository.findStuckTasks(stuckThreshold)

            stuckTasks.collect { task ->
                logger.warn { "Marking stuck task as failed: ${task.taskName}" }
                markTaskAsFailed(task, "Task stuck - exceeded timeout of ${STUCK_TASK_TIMEOUT_HOURS} hours")
            }
        } catch (e: Exception) {
            logger.error(e) { "Error cleaning up stuck tasks" }
        }
    }

    /**
     * Cancel a task
     */
    suspend fun cancelTask(taskId: ObjectId): Boolean = taskManagementService.cancelTask(taskId)
}
