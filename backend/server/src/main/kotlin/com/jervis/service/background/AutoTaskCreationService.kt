package com.jervis.service.background

import com.jervis.common.types.ClientId
import com.jervis.common.types.ProjectId
import com.jervis.common.types.SourceUrn
import com.jervis.dto.TaskStateEnum
import com.jervis.dto.TaskTypeEnum
import com.jervis.dto.pipeline.ActionType
import com.jervis.dto.pipeline.EstimatedComplexity
import com.jervis.entity.ProcessingMode
import com.jervis.entity.TaskDocument
import com.jervis.knowledgebase.model.FullIngestResult
import com.jervis.qualifier.ActionTypeInferrer
import com.jervis.repository.TaskRepository
import mu.KotlinLogging
import org.springframework.stereotype.Service

/**
 * EPIC 2-S2: Automatic task creation from qualifier findings.
 *
 * Called by KbResultRouter after successful qualification.
 * Creates the appropriate task type based on actionType and complexity:
 *
 * - CODE_FIX + TRIVIAL/SIMPLE → BACKGROUND task (auto-dispatch, no approval)
 * - CODE_FIX + MEDIUM/COMPLEX → USER_TASK with plan for approval
 * - RESPOND_EMAIL → USER_TASK with draft response for approval
 * - INVESTIGATE → BACKGROUND task for analysis
 * - CODE_REVIEW → BACKGROUND task
 * - UPDATE_DOCS → BACKGROUND task
 * - CREATE_TICKET → USER_TASK (needs approval before creating in external system)
 * - NOTIFY_ONLY → No task (notification only, handled by existing flow)
 *
 * Includes deduplication: checks if a task with the same correlationId already exists.
 */
@Service
class AutoTaskCreationService(
    private val taskService: TaskService,
    private val taskRepository: TaskRepository,
    private val priorityCalculator: TaskPriorityCalculator,
) {
    private val logger = KotlinLogging.logger {}

    /**
     * Create task(s) from qualifier finding based on inferred action type and complexity.
     *
     * @param originalTask The original task that was qualified
     * @param result KB qualification result
     * @param inferred Inferred structured fields (actionType, complexity, agent)
     * @return Created task, or null if no task needed (NOTIFY_ONLY or duplicate)
     */
    suspend fun createFromQualifierFinding(
        originalTask: TaskDocument,
        result: FullIngestResult,
        inferred: ActionTypeInferrer.InferredFields,
    ): TaskDocument? {
        // Skip if NOTIFY_ONLY — existing flow handles notifications
        if (inferred.actionType == ActionType.NOTIFY_ONLY) {
            logger.info { "AUTO_TASK_SKIP: taskId=${originalTask.id} actionType=NOTIFY_ONLY" }
            return null
        }

        // Deduplication: check if a task with the same correlation already exists
        val actionCorrelationId = "auto:${inferred.actionType.name}:${originalTask.correlationId}"
        val existing = taskRepository.findByCorrelationId(actionCorrelationId)
        if (existing != null) {
            logger.info {
                "AUTO_TASK_DEDUP: taskId=${originalTask.id} correlationId=$actionCorrelationId existing=${existing.id}"
            }
            return null
        }

        val priority = priorityCalculator.calculate(result, inferred)

        return when (inferred.actionType) {
            ActionType.CODE_FIX -> createCodeFixTask(originalTask, result, inferred, priority, actionCorrelationId)
            ActionType.CODE_REVIEW -> createBackgroundTask(originalTask, result, inferred, priority, actionCorrelationId, "Code review")
            ActionType.RESPOND_EMAIL -> createEmailResponseTask(originalTask, result, priority, actionCorrelationId)
            ActionType.UPDATE_DOCS -> createBackgroundTask(originalTask, result, inferred, priority, actionCorrelationId, "Aktualizace dokumentace")
            ActionType.CREATE_TICKET -> createTicketTask(originalTask, result, priority, actionCorrelationId)
            ActionType.INVESTIGATE -> createBackgroundTask(originalTask, result, inferred, priority, actionCorrelationId, "Analýza")
            ActionType.NOTIFY_ONLY -> null // Already handled above
        }
    }

    /**
     * CODE_FIX routing based on complexity:
     * - TRIVIAL/SIMPLE → BACKGROUND (auto-dispatch)
     * - MEDIUM/COMPLEX → USER_TASK (needs approval of plan)
     */
    private suspend fun createCodeFixTask(
        originalTask: TaskDocument,
        result: FullIngestResult,
        inferred: ActionTypeInferrer.InferredFields,
        priority: TaskPriorityCalculator.PriorityResult,
        correlationId: String,
    ): TaskDocument {
        val affectedFilesNote = if (result.affectedFiles.isNotEmpty()) {
            "\nSoubory: ${result.affectedFiles.joinToString(", ")}"
        } else ""
        val relatedNodesNote = if (result.relatedKbNodes.isNotEmpty()) {
            "\nRelated KB: ${result.relatedKbNodes.joinToString(", ")}"
        } else ""

        return when (inferred.estimatedComplexity) {
            EstimatedComplexity.TRIVIAL, EstimatedComplexity.SIMPLE -> {
                // Auto-dispatch: create BACKGROUND task that goes straight to orchestrator
                val content = buildString {
                    appendLine("## Automatický úkol: Oprava kódu")
                    appendLine()
                    appendLine("**Zdroj:** ${originalTask.sourceUrn}")
                    appendLine("**Složitost:** ${inferred.estimatedComplexity.name}")
                    appendLine("**Priorita:** ${priority.score} (${priority.reason})")
                    appendLine()
                    appendLine("### Popis")
                    appendLine(result.summary)
                    append(affectedFilesNote)
                    append(relatedNodesNote)
                    appendLine()
                    appendLine("### Původní obsah")
                    appendLine(originalTask.content.take(2000))
                }

                val task = taskService.createTask(
                    taskType = TaskTypeEnum.SCHEDULED_TASK, // Uses background processing
                    content = content,
                    clientId = ClientId(originalTask.clientId.value),
                    projectId = originalTask.projectId?.let { ProjectId(it.value) },
                    correlationId = correlationId,
                    sourceUrn = originalTask.sourceUrn,
                    attachments = originalTask.attachments,
                    taskName = "Auto: ${result.summary.take(80)}",
                    state = TaskStateEnum.QUEUED, // Skip qualification, go straight to orchestrator
                )

                // Set priority score on the created task
                updateTaskPriority(task, priority.score)

                logger.info {
                    "AUTO_TASK_CREATED: type=CODE_FIX complexity=${inferred.estimatedComplexity} " +
                        "mode=BACKGROUND taskId=${task.id} priority=${priority.score} " +
                        "original=${originalTask.id}"
                }
                task
            }

            EstimatedComplexity.MEDIUM, EstimatedComplexity.COMPLEX -> {
                // Needs planning → create USER_TASK for plan approval
                val content = buildString {
                    appendLine("## Úkol vyžadující schválení: Oprava kódu")
                    appendLine()
                    appendLine("**Zdroj:** ${originalTask.sourceUrn}")
                    appendLine("**Složitost:** ${inferred.estimatedComplexity.name}")
                    appendLine("**Priorita:** ${priority.score} (${priority.reason})")
                    appendLine()
                    appendLine("### Popis")
                    appendLine(result.summary)
                    append(affectedFilesNote)
                    append(relatedNodesNote)
                    appendLine()
                    appendLine("### Co JERVIS navrhuje udělat")
                    appendLine("Analýza a implementace opravy podle zjištění z kvalifikace.")
                    appendLine("Po schválení bude vytvořen detailní plán a spuštěn coding agent.")
                }

                val task = taskService.createTask(
                    taskType = TaskTypeEnum.USER_TASK,
                    content = content,
                    clientId = ClientId(originalTask.clientId.value),
                    projectId = originalTask.projectId?.let { ProjectId(it.value) },
                    correlationId = correlationId,
                    sourceUrn = originalTask.sourceUrn,
                    attachments = originalTask.attachments,
                    taskName = "Ke schválení: ${result.summary.take(60)}",
                    state = TaskStateEnum.USER_TASK,
                )

                updateTaskPriority(task, priority.score)

                logger.info {
                    "AUTO_TASK_CREATED: type=CODE_FIX complexity=${inferred.estimatedComplexity} " +
                        "mode=USER_TASK taskId=${task.id} priority=${priority.score} " +
                        "original=${originalTask.id}"
                }
                task
            }
        }
    }

    /**
     * Create a generic BACKGROUND task for CODE_REVIEW, UPDATE_DOCS, INVESTIGATE.
     */
    private suspend fun createBackgroundTask(
        originalTask: TaskDocument,
        result: FullIngestResult,
        inferred: ActionTypeInferrer.InferredFields,
        priority: TaskPriorityCalculator.PriorityResult,
        correlationId: String,
        label: String,
    ): TaskDocument {
        val content = buildString {
            appendLine("## Automatický úkol: $label")
            appendLine()
            appendLine("**Typ:** ${inferred.actionType.name}")
            appendLine("**Zdroj:** ${originalTask.sourceUrn}")
            appendLine("**Složitost:** ${inferred.estimatedComplexity.name}")
            appendLine("**Priorita:** ${priority.score} (${priority.reason})")
            appendLine()
            appendLine("### Popis")
            appendLine(result.summary)
            if (result.affectedFiles.isNotEmpty()) {
                appendLine("\n**Soubory:** ${result.affectedFiles.joinToString(", ")}")
            }
            appendLine()
            appendLine("### Původní obsah")
            appendLine(originalTask.content.take(2000))
        }

        val task = taskService.createTask(
            taskType = TaskTypeEnum.SCHEDULED_TASK,
            content = content,
            clientId = ClientId(originalTask.clientId.value),
            projectId = originalTask.projectId?.let { ProjectId(it.value) },
            correlationId = correlationId,
            sourceUrn = originalTask.sourceUrn,
            attachments = originalTask.attachments,
            taskName = "Auto: $label — ${result.summary.take(60)}",
            state = TaskStateEnum.QUEUED,
        )

        updateTaskPriority(task, priority.score)

        logger.info {
            "AUTO_TASK_CREATED: type=${inferred.actionType} mode=BACKGROUND taskId=${task.id} priority=${priority.score}"
        }
        return task
    }

    /**
     * RESPOND_EMAIL → USER_TASK with draft response for approval.
     */
    private suspend fun createEmailResponseTask(
        originalTask: TaskDocument,
        result: FullIngestResult,
        priority: TaskPriorityCalculator.PriorityResult,
        correlationId: String,
    ): TaskDocument {
        val content = buildString {
            appendLine("## Odpovědět na email/zprávu")
            appendLine()
            appendLine("**Zdroj:** ${originalTask.sourceUrn}")
            appendLine("**Priorita:** ${priority.score} (${priority.reason})")
            appendLine()
            appendLine("### Shrnutí")
            appendLine(result.summary)
            appendLine()
            appendLine("### Původní obsah")
            appendLine(originalTask.content.take(3000))
        }

        val task = taskService.createTask(
            taskType = TaskTypeEnum.USER_TASK,
            content = content,
            clientId = ClientId(originalTask.clientId.value),
            projectId = originalTask.projectId?.let { ProjectId(it.value) },
            correlationId = correlationId,
            sourceUrn = originalTask.sourceUrn,
            attachments = originalTask.attachments,
            taskName = "Odpovědět: ${result.summary.take(70)}",
            state = TaskStateEnum.USER_TASK,
        )

        updateTaskPriority(task, priority.score)

        logger.info {
            "AUTO_TASK_CREATED: type=RESPOND_EMAIL mode=USER_TASK taskId=${task.id} priority=${priority.score} original=${originalTask.id}"
        }
        return task
    }

    /**
     * CREATE_TICKET → USER_TASK (needs approval before creating in external bugtracker).
     */
    private suspend fun createTicketTask(
        originalTask: TaskDocument,
        result: FullIngestResult,
        priority: TaskPriorityCalculator.PriorityResult,
        correlationId: String,
    ): TaskDocument {
        val content = buildString {
            appendLine("## Vytvořit ticket v bug trackeru")
            appendLine()
            appendLine("**Zdroj:** ${originalTask.sourceUrn}")
            appendLine("**Priorita:** ${priority.score} (${priority.reason})")
            appendLine()
            appendLine("### Shrnutí")
            appendLine(result.summary)
            appendLine()
            appendLine("### Původní obsah")
            appendLine(originalTask.content.take(2000))
        }

        val task = taskService.createTask(
            taskType = TaskTypeEnum.USER_TASK,
            content = content,
            clientId = ClientId(originalTask.clientId.value),
            projectId = originalTask.projectId?.let { ProjectId(it.value) },
            correlationId = correlationId,
            sourceUrn = originalTask.sourceUrn,
            attachments = originalTask.attachments,
            taskName = "Vytvořit ticket: ${result.summary.take(60)}",
            state = TaskStateEnum.USER_TASK,
        )

        updateTaskPriority(task, priority.score)

        logger.info {
            "AUTO_TASK_CREATED: type=CREATE_TICKET mode=USER_TASK taskId=${task.id} priority=${priority.score} original=${originalTask.id}"
        }
        return task
    }

    /**
     * Set priority score on a task via MongoDB update.
     */
    private suspend fun updateTaskPriority(task: TaskDocument, score: Int) {
        // Priority stored as priorityScore field on TaskDocument
        // Will be read by getNextBackgroundTask() for ordering
        val updated = task.copy(priorityScore = score)
        taskRepository.save(updated)
    }
}
