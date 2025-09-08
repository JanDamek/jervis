package com.jervis.service.agent.execution

import com.jervis.domain.context.TaskContext
import com.jervis.domain.plan.Plan
import com.jervis.domain.plan.PlanStatus
import com.jervis.domain.plan.StepStatus
import com.jervis.service.agent.context.TaskContextService
import com.jervis.service.agent.planner.Planner
import com.jervis.service.mcp.McpToolRegistry
import com.jervis.service.mcp.domain.ToolResult
import com.jervis.service.notification.StepNotificationService
import com.jervis.service.rag.RagIngestService
import mu.KotlinLogging
import org.bson.types.ObjectId
import org.springframework.stereotype.Service
import java.time.Instant

/**
 * Executes the first pending plan step using registered MCP tools.
 */
@Service
class PlanExecutor(
    private val mcpToolRegistry: McpToolRegistry,
    private val taskContextService: TaskContextService,
    private val ragIngestService: RagIngestService,
    private val planner: Planner,
    private val stepNotificationService: StepNotificationService,
) {
    private val logger = KotlinLogging.logger {}

    suspend fun execute(context: TaskContext) {
        context.plans.filter { it.status != PlanStatus.COMPLETED }.forEach { plan ->
            logger.debug { "EXECUTOR_PLAN_START: Processing planId=${plan.id}, status=${plan.status}, steps=${plan.steps.size}" }
            logger.debug {
                "EXECUTOR_PLAN_STEPS: ${plan.steps.map { "stepId=${it.id}, order=${it.order}, name=${it.name}, status=${it.status}" }}"
            }

            if (plan.steps.isEmpty()) {
                logger.error { "EXECUTOR_EMPTY_PLAN: Plan ${plan.id} has no steps - marking as FAILED instead of COMPLETED" }
                plan.status = PlanStatus.FAILED
                plan.finalAnswer = "Plan has no executable steps. This indicates a planner error."
                plan.updatedAt = Instant.now()
                taskContextService.save(context)
            } else {
                plan.status = PlanStatus.RUNNING
                plan.updatedAt = Instant.now()
                taskContextService.save(context)
            }

            try {
                plan.steps.sortedBy { it.order }.first { it.status == StepStatus.PENDING }.let { step ->
                    mcpToolRegistry.byName(step.name).let { tool ->
                        logger.info { "EXECUTOR_STEP_START: stepId=${step.id} step='${step.name}' plan=${plan.id}" }
                        logger.debug { "EXECUTOR_STEP_TOOL: tool='${tool.name}', taskDescription=${step.taskDescription}" }

                        val result = tool.execute(context = context, plan, taskDescription = step.taskDescription)
                        logger.info { "EXECUTOR: Tool '${tool.name}' finished with output='${result.output.take(200)}'" }

                        when (result) {
                            is ToolResult.Ok, is ToolResult.Ask -> {
                                step.output = result
                                step.status = StepStatus.DONE
                                appendSummaryLine(plan, step.id, step.name, result.output)
                                stepNotificationService.notifyStepCompleted(context.id, plan.id, step)
                            }

                            is ToolResult.Stop -> {
                                step.output = result
                                step.status = StepStatus.FAILED
                                plan.status = PlanStatus.FAILED
                                plan.finalAnswer = result.reason
                                appendSummaryLine(plan, step.id, step.name, "STOPPED: ${result.reason}")
                                logger.debug { "EXECUTOR_STOPPED: Plan execution halted due to unresolvable error: ${result.reason}" }
                                stepNotificationService.notifyStepCompleted(context.id, plan.id, step)
                                stepNotificationService.notifyPlanStatusChanged(context.id, plan.id, plan.status)
                            }

                            is ToolResult.Error -> {
                                step.output = result
                                step.status = StepStatus.FAILED
                                val reason = result.errorMessage ?: "Unknown error"
                                appendSummaryLine(plan, step.id, step.name, "ERROR: $reason")
                                stepNotificationService.notifyStepCompleted(context.id, plan.id, step)

                                // Check for cycles - if same tool failed multiple times with similar parameters, stop
                                val failedStepsWithSameTool =
                                    plan.steps.filter {
                                        it.status == StepStatus.FAILED && it.name == step.name
                                    }
                                if (failedStepsWithSameTool.size >= 3) {
                                    plan.status = PlanStatus.FAILED
                                    plan.finalAnswer =
                                        "Cycle detected: Tool '${step.name}' failed ${failedStepsWithSameTool.size} times. Stopping to prevent infinite loop."
                                    logger.info {
                                        "EXECUTOR_CYCLE_DETECTED: Tool '${step.name}' failed ${failedStepsWithSameTool.size} times, stopping plan execution"
                                    }
                                    stepNotificationService.notifyPlanStatusChanged(context.id, plan.id, plan.status)
                                    return@forEach
                                }

                                // Trigger replanning instead of failing
                                logger.debug { "EXECUTOR_REPLANNING: Triggering replanning due to error in step '${step.name}': $reason" }
                                try {
                                    val completedSteps = plan.steps.filter { it.status == StepStatus.DONE }
                                    val failedSteps = plan.steps.filter { it.status == StepStatus.FAILED }
                                    val preservedSteps = (completedSteps + failedSteps).sortedBy { it.order }
                                    val replanedPlan = planner.createPlan(context, plan)
                                    val newSteps =
                                        replanedPlan.steps.map { newStep ->
                                            newStep.copy(order = newStep.order + preservedSteps.size)
                                        }

                                    plan.steps = (preservedSteps + newSteps).sortedBy { it.order }
                                    logger.debug {
                                        "EXECUTOR_REPLANNING_SUCCESS: Plan replanned - preserved ${completedSteps.size} completed steps, ${failedSteps.size} failed steps, added ${newSteps.size} new steps"
                                    }
                                } catch (e: Exception) {
                                    logger.error(e) { "EXECUTOR_REPLANNING_FAILED: Falling back to marking plan as failed" }
                                    plan.status = PlanStatus.FAILED
                                    plan.finalAnswer = "Original error: $reason. Replanning failed: ${e.message}"
                                    stepNotificationService.notifyPlanStatusChanged(context.id, plan.id, plan.status)
                                }
                            }
                        }

                        taskContextService.save(context)
                    }
                }
            } catch (e: NoSuchElementException) {
                val pendingSteps = plan.steps.filter { it.status == StepStatus.PENDING }
                val doneSteps = plan.steps.filter { it.status == StepStatus.DONE }
                val failedSteps = plan.steps.filter { it.status == StepStatus.FAILED }

                logger.debug {
                    "EXECUTOR_NO_PENDING_STEPS: Plan ${plan.id} - pendingSteps=${pendingSteps.size}, doneSteps=${doneSteps.size}, failedSteps=${failedSteps.size}"
                }

                if (doneSteps.isNotEmpty() || failedSteps.isNotEmpty()) {
                    logger.info { "EXECUTOR_PLAN_COMPLETED: Plan ${plan.id} completed - all steps processed" }
                    plan.status = PlanStatus.COMPLETED
                    stepNotificationService.notifyPlanStatusChanged(context.id, plan.id, plan.status)
                } else {
                    logger.error {
                        "EXECUTOR_NO_STEPS_PROCESSED: Plan ${plan.id} has no pending steps but also no processed steps - this should not happen"
                    }
                    plan.status = PlanStatus.FAILED
                    plan.finalAnswer =
                        "No pending steps found and no steps were processed. This indicates a planning or execution error."
                    stepNotificationService.notifyPlanStatusChanged(context.id, plan.id, plan.status)
                }
            }
            plan.updatedAt = Instant.now()
        }
        taskContextService.save(context)
    }

    private fun appendSummaryLine(
        plan: Plan,
        stepId: ObjectId,
        toolName: String,
        message: String,
    ) {
        val line = "Step $stepId: $toolName → ${message.take(1000)}"
        val prefix = plan.contextSummary?.takeIf { it.isNotBlank() }?.plus("\n") ?: ""
        plan.contextSummary = prefix + line
    }
}
