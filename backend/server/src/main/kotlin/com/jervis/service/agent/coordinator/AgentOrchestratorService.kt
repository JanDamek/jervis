package com.jervis.service.agent.coordinator

import com.jervis.configuration.prompts.PromptTypeEnum
import com.jervis.configuration.prompts.ToolTypeEnum
import com.jervis.domain.plan.Plan
import com.jervis.domain.plan.PlanStep
import com.jervis.domain.task.PendingTask
import com.jervis.dto.ChatRequestContext
import com.jervis.dto.ChatResponse
import com.jervis.repository.ClientMongoRepository
import com.jervis.repository.ProjectMongoRepository
import com.jervis.service.agent.compaction.ContextCompactionService
import com.jervis.service.agent.execution.PlanExecutor
import com.jervis.service.agent.finalizer.Finalizer
import com.jervis.service.agent.planner.Planner
import com.jervis.service.agent.toolreasoning.ToolReasoningService
import com.jervis.service.debug.DebugService
import com.jervis.service.notification.NotificationsPublisher
import com.jervis.service.notification.StepNotificationService
import com.jervis.service.prompts.PromptRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import mu.KotlinLogging
import org.bson.types.ObjectId
import org.springframework.stereotype.Service

/**
 * Main orchestrator for incoming chat requests.
 * Works with runtime-only Plan objects (no persistence).
 * Each call to handle() creates and executes a new plan.
 */
@Service
class AgentOrchestratorService(
    private val planExecutor: PlanExecutor,
    private val finalizer: Finalizer,
    private val requestAnalyzer: RequestAnalyzer,
    private val planner: Planner,
    private val toolReasoningService: ToolReasoningService,
    private val contextCompactionService: ContextCompactionService,
    private val stepNotificationService: StepNotificationService,
    private val clientMongoRepository: ClientMongoRepository,
    private val projectMongoRepository: ProjectMongoRepository,
    private val debugService: DebugService,
    private val notificationsPublisher: NotificationsPublisher,
    private val promptRepository: PromptRepository,
) {
    private val logger = KotlinLogging.logger {}

    // Map to store active plans by correlationId for progress tracking on interruption
    private val activePlans = java.util.concurrent.ConcurrentHashMap<String, Plan>()

    suspend fun handle(
        text: String,
        ctx: ChatRequestContext,
        background: Boolean,
    ): ChatResponse =
        handle(
            text = text,
            clientId = ObjectId(ctx.clientId),
            projectId = ObjectId(ctx.projectId),
            background = background,
        )

    suspend fun handle(
        text: String,
        clientId: ObjectId,
        projectId: ObjectId?,
        background: Boolean,
    ): ChatResponse = handleWithGoalPrompt(text, clientId, projectId, background, null, null)

    private suspend fun handleWithGoalPrompt(
        text: String,
        clientId: ObjectId,
        projectId: ObjectId?,
        background: Boolean,
        goalPrompt: String?,
        taskCorrelationId: String?, // Pass correlationId from task if available
    ): ChatResponse {
        val projectIdLog = projectId?.toHexString() ?: "none"
        logger.info { "AGENT_START: Handling query for client='${clientId.toHexString()}', project='$projectIdLog'" }

        try {
            // Generate correlationId early to use in analysis
            val correlationId = taskCorrelationId ?: ObjectId.get().toHexString()

            val analysisResult =
                requestAnalyzer.analyze(
                    text = text,
                    backgroundMode = background,
                    goalPrompt = goalPrompt,
                    correlationId = correlationId,
                )

            // Load client document (mandatory)
            val clientDocument =
                clientMongoRepository.findById(clientId)
                    ?: throw IllegalArgumentException("Client with ID $clientId not found")

            // Load project document (optional - only if projectId provided)
            val projectDocument = projectId?.let { projectMongoRepository.findById(it) }

            val plan =
                Plan(
                    id = ObjectId.get(),
                    taskInstruction = text,
                    originalLanguage = analysisResult.originalLanguage,
                    englishInstruction = analysisResult.englishText,
                    questionChecklist = analysisResult.questionChecklist,
                    initialRagQueries = analysisResult.initialRagQueries,
                    clientDocument = clientDocument,
                    projectDocument = projectDocument,
                    backgroundMode = background,
                    correlationId = correlationId, // Use correlationId generated earlier
                )

            // Store plan in active plans map for progress tracking
            activePlans[correlationId] = plan

            // Publish debug event for plan creation
            // Note: taskId will be passed if this comes from background task, null for chat requests
            debugService.planCreated(
                correlationId = plan.correlationId,
                planId = plan.id.toHexString(),
                taskId = if (background && taskCorrelationId != null) taskCorrelationId else null,
                taskInstruction = plan.taskInstruction,
                backgroundMode = background,
            )

            executeInitialRagQueries(plan)

            // Mark the plan as RUNNING before entering the planning loop
            plan.status = com.jervis.domain.plan.PlanStatusEnum.RUNNING
            stepNotificationService.notifyPlanStatusChanged(plan.id, plan.id, plan.status)

            // Publish debug event for plan status change
            debugService.planStatusChanged(
                correlationId = plan.correlationId,
                planId = plan.id.toHexString(),
                status = plan.status.name,
            )

            logger.info {
                "AGENT_LOOP_START: Planning loop for plan: ${plan.id} taskInstruction='${plan.taskInstruction}' correlationId=${plan.correlationId}"
            }

            // Iterative execution: execute steps, plan next steps, check resolution
            while (true) {
                // First, execute any pending steps
                val hasPendingSteps =
                    plan.steps.any { it.status == com.jervis.domain.plan.StepStatusEnum.PENDING }

                if (hasPendingSteps) {
                    logger.debug { "AGENT_LOOP_EXECUTING: Executing pending steps before planning next steps" }
                    planExecutor.executePlan(plan)
                    continue
                }

                // All steps executed - ask planner for next steps
                logger.debug { "AGENT_LOOP_PLANNING: All steps executed, asking planner for next steps" }

                if (plan.status != com.jervis.domain.plan.PlanStatusEnum.RUNNING) {
                    // Plan is no longer a running-exit loop to finalize
                    logger.info { "AGENT_LOOP_NO_RUNNING_PLAN: Plan not running, exiting loop" }
                    break
                }

                // Validate plan is not COMPLETED before adding steps
                if (plan.status == com.jervis.domain.plan.PlanStatusEnum.COMPLETED) {
                    logger.warn { "AGENT_LOOP_STEPS_REJECTED: Cannot add steps to COMPLETED plan ${plan.id}" }
                    break
                }

                // PHASE 1: Planner suggests what information is needed
                val plannerResponse = planner.suggestNextSteps(plan)

                if (plannerResponse.nextSteps.isEmpty()) {
                    logger.info { "AGENT_LOOP_RESOLVED: Task fully resolved, marking plan as COMPLETED" }
                    val hasSteps = plan.steps.isNotEmpty()
                    val noPendingSteps =
                        plan.steps.none { it.status == com.jervis.domain.plan.StepStatusEnum.PENDING }

                    if (hasSteps && noPendingSteps) {
                        plan.status = com.jervis.domain.plan.PlanStatusEnum.COMPLETED
                        logger.info { "AGENT_LOOP_PLAN_COMPLETED: Plan ${plan.id} marked as COMPLETED" }

                        // Publish debug event for plan status change
                        debugService.planStatusChanged(
                            correlationId = plan.correlationId,
                            planId = plan.id.toHexString(),
                            status = plan.status.name,
                        )
                    } else {
                        val reason =
                            when {
                                !hasSteps -> "has no steps"
                                else -> "has pending steps"
                            }
                        logger.warn {
                            "AGENT_LOOP_PLAN_NOT_COMPLETED: Plan ${plan.id} $reason, cannot mark as COMPLETED"
                        }
                    }
                    break
                }

                // PHASE 2: Tool Reasoning maps requirements to tools
                logger.debug { "AGENT_LOOP_TOOL_REASONING: Mapping ${plannerResponse.nextSteps.size} requirements to tools" }
                val nextSteps = toolReasoningService.mapRequirementsToTools(plannerResponse.nextSteps, plan)

                if (nextSteps.isNotEmpty()) {
                    logger.info {
                        "AGENT_LOOP_STEPS_ADDED: planId=${plan.id} newSteps=${nextSteps.size} totalSteps=${plan.steps.size + nextSteps.size}"
                    }
                    plan.steps += nextSteps

                    // Publish debug event for each step added
                    nextSteps.forEach { step ->
                        debugService.planStepAdded(
                            correlationId = plan.correlationId,
                            planId = plan.id.toHexString(),
                            stepId = step.id.toHexString(),
                            toolName = step.stepToolName.name,
                            instruction = step.stepInstruction,
                            order = step.order,
                        )
                    }

                    // PHASE 3: Check context and compact in loop until context is within limits
                    // Continue compacting until:
                    // 1. Context is within limits (OK - continue)
                    // 2. Context still too large BUT only 3 or fewer steps remain (FAIL - cannot compact further)
                    var compactionRound = 0

                    while (true) {
                        val compactionResult = contextCompactionService.checkAndCompact(plan)

                        if (!compactionResult.needsCompaction) {
                            // Context is within limits, no compaction needed
                            logger.debug {
                                "AGENT_LOOP_CONTEXT_OK: Plan ${plan.id} context is within limits after $compactionRound compaction round(s)"
                            }
                            break
                        }

                        if (compactionResult.cannotCompact) {
                            // Context too large but cannot compact (≤3 steps remaining)
                            logger.error {
                                "AGENT_LOOP_COMPACTION_IMPOSSIBLE: Plan ${plan.id} context too large but only ${plan.steps.size} steps remain (minimum 3 required for compaction)"
                            }
                            throw IllegalStateException(
                                "Context too large for planner model but cannot compact further: " +
                                    "only ${plan.steps.size} steps remain (need >3 for compaction)",
                            )
                        }

                        compactionRound++
                        logger.info {
                            "AGENT_LOOP_CONTEXT_COMPACTED: Plan ${plan.id} context compacted (round $compactionRound), checking again..."
                        }

                        // Loop continues - check context again after compaction
                    }
                } else {
                    logger.info { "AGENT_LOOP_RESOLVED: Task fully resolved, marking plan as COMPLETED" }
                    val hasSteps = plan.steps.isNotEmpty()
                    val noPendingSteps =
                        plan.steps.none { it.status == com.jervis.domain.plan.StepStatusEnum.PENDING }

                    if (hasSteps && noPendingSteps) {
                        plan.status = com.jervis.domain.plan.PlanStatusEnum.COMPLETED
                        logger.info { "AGENT_LOOP_PLAN_COMPLETED: Plan ${plan.id} marked as COMPLETED" }

                        // Publish debug event for plan status change
                        debugService.planStatusChanged(
                            correlationId = plan.correlationId,
                            planId = plan.id.toHexString(),
                            status = plan.status.name,
                        )
                    } else {
                        val reason =
                            when {
                                !hasSteps -> "has no steps"
                                else -> "has pending steps"
                            }
                        logger.warn {
                            "AGENT_LOOP_PLAN_NOT_COMPLETED: Plan ${plan.id} $reason, cannot mark as COMPLETED"
                        }
                    }
                    break
                }
            }

            // Skip finalizer for background tasks - they complete with an empty plan
            val response =
                if (background) {
                    logger.info { "AGENT_BACKGROUND_COMPLETE: Background task completed without finalizer" }
                    ChatResponse(
                        message = "Background task completed",
                    )
                } else {
                    val finalResponse = finalizer.finalize(plan)

                    // Notify UI about finalization if the plan is marked as FINALIZED
                    if (plan.status == com.jervis.domain.plan.PlanStatusEnum.FINALIZED) {
                        stepNotificationService.notifyPlanStatusChanged(plan.id, plan.id, plan.status)

                        // Publish debug event for plan status change
                        debugService.planStatusChanged(
                            correlationId = plan.correlationId,
                            planId = plan.id.toHexString(),
                            status = plan.status.name,
                        )
                    }

                    finalResponse
                }

            logger.info { "AGENT_END: Final response generated." }
            logger.debug { "AGENT_FINAL_RESPONSE: \"${response.message}\"" }

            // Broadcast final response to all connected clients via WebSocket notifications (single-user design)
            if (!background) {
                notificationsPublisher.publishAgentResponseCompleted(
                    contextId = plan.id,
                    message = response.message,
                    timestamp =
                        java.time.LocalDateTime
                            .now()
                            .toString(),
                )
            }

            // Remove plan from active plans after completion
            activePlans.remove(correlationId)

            return response
        } catch (e: Exception) {
            val projectIdLog = projectId?.toHexString() ?: "none"
            logger.error(e) {
                "AGENT_ERROR: Error handling query for client='${clientId.toHexString()}', project='$projectIdLog': ${e.message}"
            }
            // Don't remove from activePlans on exception - may be CancellationException (interruption)
            throw e
        }
    }

    suspend fun handleBackgroundTask(task: PendingTask): ChatResponse {
        logger.info {
            "AGENT_BACKGROUND_START: taskId=${task.id} type=${task.taskType} clientId=${task.clientId.toHexString()} projectId=${task.projectId?.toHexString() ?: "none"} correlationId=${task.correlationId}"
        }

        val clientId = requireNotNull(task.clientId) { "Background task must have clientId" }
        val projectId = task.projectId

        // Use content-only policy: model works with goals + raw content, nothing else
        val taskText = task.content

        // Load task-specific goal from YAML and apply content placeholder if present
        val goalTemplate = promptRepository.getGoals(task.taskType)
        val goalPrompt = goalTemplate.replace("{content}", taskText)
        logger.info { "AGENT_BACKGROUND_GOAL: taskId=${task.id} goalLength=${goalPrompt.length} contentLength=${taskText.length}" }

        return try {
            val response =
                handleWithGoalPrompt(
                    text = taskText,
                    clientId = clientId,
                    projectId = projectId,
                    background = true,
                    goalPrompt = goalPrompt,
                    taskCorrelationId = task.correlationId, // Propagate correlationId from task to plan
                )

            response
        } catch (e: Exception) {
            logger.error(e) { "AGENT_BACKGROUND_ERROR: Failed to process task ${task.id}: ${e.message}" }
            throw e
        }
    }

    private suspend fun executeInitialRagQueries(plan: Plan) {
        if (plan.initialRagQueries.isEmpty()) return

        coroutineScope {
            val searchSteps =
                plan.initialRagQueries.mapIndexed { index, query ->
                    PlanStep(
                        id = ObjectId.get(),
                        order = index,
                        stepToolName = ToolTypeEnum.KNOWLEDGE_SEARCH_TOOL,
                        stepInstruction = query,
                    )
                }

            val searchJobs =
                searchSteps.map { step ->
                    async {
                        planExecutor.executeOneStep(step, plan)
                    }
                }

            searchJobs.forEach { it.await() }
            plan.steps += searchSteps
        }
    }

    /**
     * Get serialized plan context for interrupted task resumption.
     * Returns null if plan not found or has no progress.
     */
    fun getLastPlanContext(correlationId: String): String? {
        val plan = activePlans[correlationId] ?: return null

        if (plan.steps.isEmpty()) {
            return null // No progress to save
        }

        val progressSummary = buildString {
            appendLine()
            appendLine("=== PREVIOUS EXECUTION PROGRESS (INTERRUPTED) ===")
            appendLine()
            appendLine("Task was interrupted by foreground request. Resuming from saved state.")
            appendLine()
            appendLine("Completed steps (${plan.steps.count { it.status == com.jervis.domain.plan.StepStatusEnum.DONE }}):")
            plan.steps
                .filter { it.status == com.jervis.domain.plan.StepStatusEnum.DONE }
                .forEachIndexed { index, step ->
                    appendLine("${index + 1}. ${step.stepToolName}: ${step.stepInstruction}")
                    step.toolResult?.let { result ->
                        val preview = result.output.take(200)
                        appendLine("   Result: $preview${if (result.output.length > 200) "..." else ""}")
                    }
                }

            if (plan.contextSummary != null) {
                appendLine()
                appendLine("Context summary:")
                appendLine(plan.contextSummary)
            }

            appendLine()
            appendLine("=== END OF PREVIOUS PROGRESS ===")
            appendLine()
        }

        // Remove from active plans now that we've captured the context
        activePlans.remove(correlationId)

        return progressSummary
    }
}
