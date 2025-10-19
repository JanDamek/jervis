package com.jervis.service.agent.coordinator

import com.jervis.configuration.prompts.PromptTypeEnum
import com.jervis.domain.context.TaskContext
import com.jervis.domain.plan.Plan
import com.jervis.domain.plan.PlanStep
import com.jervis.dto.ChatRequestContext
import com.jervis.dto.ChatResponse
import com.jervis.entity.mongo.PlanDocument
import com.jervis.repository.mongo.PlanMongoRepository
import com.jervis.service.agent.context.TaskContextService
import com.jervis.service.agent.execution.PlanExecutor
import com.jervis.service.agent.finalizer.Finalizer
import com.jervis.service.agent.planner.Planner
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import mu.KotlinLogging
import org.bson.types.ObjectId
import org.springframework.stereotype.Service

/**
 * Main orchestrator for incoming chat requests.
 * Supports concurrent execution of multiple plans within a single context.
 * Each call to handle() creates a new plan that can be executed alongside existing plans.
 * The planning loop processes all RUNNING plans concurrently, allowing for parallel task resolution.
 */
@Service
class AgentOrchestratorService(
    private val taskContextService: TaskContextService,
    private val planExecutor: PlanExecutor,
    private val finalizer: Finalizer,
    private val languageOrchestrator: LanguageOrchestrator,
    private val planner: Planner,
    private val planMongoRepository: PlanMongoRepository,
    private val stepNotificationService: com.jervis.service.notification.StepNotificationService,
) {
    private val logger = KotlinLogging.logger {}

    suspend fun handle(
        text: String,
        ctx: ChatRequestContext,
    ): ChatResponse =
        handle(
            text = text,
            clientId = ObjectId(ctx.clientId),
            projectId = ObjectId(ctx.projectId),
            quick = ctx.quick,
            existingContextId = ctx.existingContextId?.let { ObjectId(it) },
        )

    suspend fun handle(
        text: String,
        clientId: ObjectId,
        projectId: ObjectId,
        quick: Boolean = false,
        existingContextId: ObjectId? = null,
    ): ChatResponse {
        logger.info { "AGENT_START: Handling query for client='${clientId.toHexString()}', project='${projectId.toHexString()}'" }

        try {
            val detectionResult =
                languageOrchestrator.translate(
                    text = text,
                    quick = quick,
                )

            val contextName =
                detectionResult.contextName.ifBlank {
                    text.take(50).trim().ifBlank { "New Context" }
                }

            val context =
                if (existingContextId != null) {
                    taskContextService.findById(existingContextId)?.apply {
                        if (this.name == "New Context") {
                            this.name = contextName
                        }
                    } ?: throw IllegalArgumentException("Context with ID $existingContextId not found")
                } else {
                    taskContextService.create(
                        clientId = clientId,
                        projectId = projectId,
                        quick = quick,
                        contextName = contextName,
                    )
                }

            val plan =
                Plan(
                    id = ObjectId.get(),
                    contextId = context.id,
                    originalQuestion = text,
                    originalLanguage = detectionResult.originalLanguage,
                    englishQuestion = detectionResult.englishText,
                    questionChecklist = detectionResult.questionChecklist,
                    initialRagQueries = detectionResult.initialRagQueries,
                )
            context.plans += plan
            planMongoRepository.save(PlanDocument.fromDomain(plan))

            findingInRAG(context, plan)

            // Mark plan as RUNNING before entering the planning loop
            plan.status = com.jervis.domain.plan.PlanStatusEnum.RUNNING
            plan.updatedAt = java.time.Instant.now()
            taskContextService.save(context)
            planMongoRepository.save(PlanDocument.fromDomain(plan))

            logger.info { "AGENT_LOOP_START: Planning loop for context: ${context.id}" }

            // Iterative execution: execute steps, plan next steps, check resolution
            while (true) {
                // First, execute any pending steps across ALL plans in the context
                val hasPendingSteps =
                    context.plans.any { p ->
                        p.steps.any { it.status == com.jervis.domain.plan.StepStatusEnum.PENDING }
                    }

                if (hasPendingSteps) {
                    logger.debug { "AGENT_LOOP_EXECUTING: Executing pending steps before planning next steps" }
                    planExecutor.execute(context)
                    continue
                }

                // All steps executed - ask planner for next steps for ALL RUNNING plans
                logger.debug { "AGENT_LOOP_PLANNING: All steps executed, asking planner for next steps" }
                val runningPlans = context.plans.filter { it.status == com.jervis.domain.plan.PlanStatusEnum.RUNNING }

                if (runningPlans.isEmpty()) {
                    // No running plans - exit loop to finalize
                    logger.info { "AGENT_LOOP_NO_RUNNING_PLANS: No running plans, exiting loop" }
                    break
                }

                // Ask planner for next steps for each running plan
                var anyNewSteps = false
                for (runningPlan in runningPlans) {
                    // Validate plan is not COMPLETED before adding steps
                    if (runningPlan.status == com.jervis.domain.plan.PlanStatusEnum.COMPLETED) {
                        logger.warn { "AGENT_LOOP_STEPS_REJECTED: Cannot add steps to COMPLETED plan ${runningPlan.id}" }
                        continue
                    }

                    val nextSteps = planner.suggestNextSteps(context, runningPlan)
                    if (nextSteps.isNotEmpty()) {
                        logger.debug { "AGENT_LOOP_STEPS_ADDED: Adding ${nextSteps.size} new steps to plan ${runningPlan.id}" }
                        runningPlan.steps += nextSteps
                        planMongoRepository.save(PlanDocument.fromDomain(runningPlan))
                        anyNewSteps = true
                    }
                }

                if (!anyNewSteps) {
                    logger.info { "AGENT_LOOP_RESOLVED: Task fully resolved, marking plans as COMPLETED" }
                    context.plans
                        .filter { it.status == com.jervis.domain.plan.PlanStatusEnum.RUNNING }
                        .forEach { runningPlan ->
                            val hasSteps = runningPlan.steps.isNotEmpty()
                            val noPendingSteps =
                                runningPlan.steps.none { it.status == com.jervis.domain.plan.StepStatusEnum.PENDING }

                            if (hasSteps && noPendingSteps) {
                                runningPlan.status = com.jervis.domain.plan.PlanStatusEnum.COMPLETED
                                runningPlan.updatedAt = java.time.Instant.now()
                                logger.info { "AGENT_LOOP_PLAN_COMPLETED: Plan ${runningPlan.id} marked as COMPLETED" }
                            } else {
                                val reason =
                                    when {
                                        !hasSteps -> "has no steps"
                                        else -> "has pending steps"
                                    }
                                logger.warn {
                                    "AGENT_LOOP_PLAN_NOT_COMPLETED: Plan ${runningPlan.id} $reason, cannot mark as COMPLETED"
                                }
                            }
                        }
                    taskContextService.save(context)
                    break
                }

                // Save context after adding steps to all plans
                taskContextService.save(context)
            }

            val response = finalizer.finalize(context)
            taskContextService.save(context)

            // Notify UI about finalization for each plan now marked as FINALIZED
            context.plans
                .filter { it.status == com.jervis.domain.plan.PlanStatusEnum.FINALIZED }
                .forEach { finalizedPlan ->
                    stepNotificationService.notifyPlanStatusChanged(context.id, finalizedPlan.id, finalizedPlan.status)
                }

            logger.info { "AGENT_END: Final response generated." }
            logger.debug { "AGENT_FINAL_RESPONSE: \"${response.message}\"" }
            return response
        } catch (e: Exception) {
            logger.error(e) {
                "AGENT_ERROR: Error handling query for client='${clientId.toHexString()}', project='${projectId.toHexString()}': ${e.message}"
            }
            throw e
        }
    }

    private suspend fun findingInRAG(
        context: TaskContext,
        plan: Plan,
    ) {
        if (plan.initialRagQueries.isEmpty()) return

        coroutineScope {
            val searchSteps =
                plan.initialRagQueries.mapIndexed { index, query ->
                    PlanStep(
                        id = ObjectId.get(),
                        order = index,
                        stepToolName = PromptTypeEnum.KNOWLEDGE_SEARCH_TOOL,
                        stepInstruction = query,
                        planId = plan.id,
                        contextId = context.id,
                    )
                }

            val searchJobs =
                searchSteps.map { step ->
                    async {
                        planExecutor.executeOneStep(step, plan, context)
                    }
                }

            searchJobs.forEach { it.await() }
            plan.steps += searchSteps
        }
    }
}
