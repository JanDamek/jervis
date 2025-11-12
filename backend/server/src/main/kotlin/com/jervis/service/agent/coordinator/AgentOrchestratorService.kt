package com.jervis.service.agent.coordinator

import com.jervis.configuration.prompts.PromptTypeEnum
import com.jervis.domain.plan.Plan
import com.jervis.domain.plan.PlanStep
import com.jervis.domain.task.PendingTask
import com.jervis.dto.ChatRequestContext
import com.jervis.dto.ChatResponse
import com.jervis.repository.mongo.ClientMongoRepository
import com.jervis.repository.mongo.ProjectMongoRepository
import com.jervis.service.agent.execution.PlanExecutor
import com.jervis.service.agent.finalizer.Finalizer
import com.jervis.service.agent.planner.Planner
import com.jervis.service.background.BackgroundTaskGoalsService
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
    private val stepNotificationService: com.jervis.service.notification.StepNotificationService,
    private val clientMongoRepository: ClientMongoRepository,
    private val projectMongoRepository: ProjectMongoRepository,
    private val backgroundTaskGoalsService: BackgroundTaskGoalsService,
) {
    private val logger = KotlinLogging.logger {}

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
            quick = ctx.quick,
        )

    suspend fun handle(
        text: String,
        clientId: ObjectId,
        projectId: ObjectId?,
        background: Boolean,
        quick: Boolean = false,
    ): ChatResponse = handleWithGoalPrompt(text, clientId, projectId, background, quick, null)

    private suspend fun handleWithGoalPrompt(
        text: String,
        clientId: ObjectId,
        projectId: ObjectId?,
        background: Boolean,
        quick: Boolean,
        goalPrompt: String?,
    ): ChatResponse {
        val projectIdLog = projectId?.toHexString() ?: "none"
        logger.info { "AGENT_START: Handling query for client='${clientId.toHexString()}', project='$projectIdLog'" }

        try {
            val analysisResult =
                requestAnalyzer.analyze(
                    text = text,
                    quick = quick,
                    backgroundMode = background,
                    goalPrompt = goalPrompt,
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
                    quick = quick,
                    backgroundMode = background,
                )

            executeInitialRagQueries(plan)

            // Mark the plan as RUNNING before entering the planning loop
            plan.status = com.jervis.domain.plan.PlanStatusEnum.RUNNING

            logger.info { "AGENT_LOOP_START: Planning loop for plan: ${plan.id}" }

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

                val nextSteps = planner.suggestNextSteps(plan)
                if (nextSteps.isNotEmpty()) {
                    logger.debug { "AGENT_LOOP_STEPS_ADDED: Adding ${nextSteps.size} new steps to plan ${plan.id}" }
                    plan.steps += nextSteps
                } else {
                    logger.info { "AGENT_LOOP_RESOLVED: Task fully resolved, marking plan as COMPLETED" }
                    val hasSteps = plan.steps.isNotEmpty()
                    val noPendingSteps =
                        plan.steps.none { it.status == com.jervis.domain.plan.StepStatusEnum.PENDING }

                    if (hasSteps && noPendingSteps) {
                        plan.status = com.jervis.domain.plan.PlanStatusEnum.COMPLETED
                        logger.info { "AGENT_LOOP_PLAN_COMPLETED: Plan ${plan.id} marked as COMPLETED" }
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
                    }

                    finalResponse
                }

            logger.info { "AGENT_END: Final response generated." }
            logger.debug { "AGENT_FINAL_RESPONSE: \"${response.message}\"" }
            return response
        } catch (e: Exception) {
            val projectIdLog = projectId?.toHexString() ?: "none"
            logger.error(e) {
                "AGENT_ERROR: Error handling query for client='${clientId.toHexString()}', project='$projectIdLog': ${e.message}"
            }
            throw e
        }
    }

    suspend fun handleBackgroundTask(task: PendingTask): ChatResponse {
        logger.info { "AGENT_BACKGROUND_START: Processing pending task ${task.id} type=${task.taskType}" }

        val clientId = requireNotNull(task.clientId) { "Background task must have clientId" }
        val projectId = task.projectId

        // Use content-only policy: model works with goals + raw content, nothing else
        val taskText = task.content

        // Load task-specific goal from YAML and apply content placeholder if present
        val goalTemplate = backgroundTaskGoalsService.getGoals(task.taskType)
        val goalPrompt = goalTemplate.replace("{content}", taskText)
        logger.info { "AGENT_BACKGROUND_GOAL: Loaded goal template for task ${task.id} (length=${goalPrompt.length})" }

        return try {
            val response =
                handleWithGoalPrompt(
                    text = taskText,
                    clientId = clientId,
                    projectId = projectId,
                    quick = false,
                    background = true,
                    goalPrompt = goalPrompt,
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
                        stepToolName = PromptTypeEnum.KNOWLEDGE_SEARCH_TOOL,
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
}
