package com.jervis.service.agent.execution

import com.jervis.domain.plan.Plan
import com.jervis.domain.plan.PlanStatusEnum
import com.jervis.domain.plan.PlanStep
import com.jervis.domain.plan.StepStatusEnum
import com.jervis.service.mcp.McpToolRegistry
import com.jervis.service.mcp.domain.ToolResult
import com.jervis.service.notification.StepNotificationService
import mu.KotlinLogging
import org.springframework.stereotype.Service

/**
 * Executor that processes plan steps.
 * Each plan is executed with its steps processed sequentially.
 * Handles step execution, plan status updates, and manages tool results.
 */
@Service
class PlanExecutor(
    private val mcpToolRegistry: McpToolRegistry,
    private val stepNotificationService: StepNotificationService,
) {
    private val logger = KotlinLogging.logger {}

    suspend fun executePlan(plan: Plan) {
        logger.info { "EXECUTOR: Starting plan execution - planId=${plan.id}, steps=${plan.steps.size}" }

        if (plan.steps.isEmpty()) {
            logger.warn { "EXECUTOR: Plan ${plan.id} has no steps - marking as FAILED" }
            plan.status = PlanStatusEnum.FAILED
            plan.finalAnswer = "Plan has no executable steps"
            stepNotificationService.notifyPlanStatusChanged(plan.id, plan.id, plan.status)
            return
        }

        plan.status = PlanStatusEnum.RUNNING
        stepNotificationService.notifyPlanStatusChanged(plan.id, plan.id, plan.status)

        // Get pending steps sorted by order for flat sequential execution
        val pendingSteps =
            plan.steps
                .filter { it.status == StepStatusEnum.PENDING }
                .sortedBy { it.order }

        logger.info { "EXECUTOR: Processing ${pendingSteps.size} pending steps" }

        pendingSteps.forEach { step ->
            executeOneStep(step, plan)
        }

        stepNotificationService.notifyPlanStatusChanged(plan.id, plan.id, plan.status)

        logger.info { "EXECUTOR: Plan execution finished - planId=${plan.id}, status=${plan.status}" }
    }

    suspend fun executeOneStep(
        step: PlanStep,
        plan: Plan,
    ): Boolean {
        var planFailed: Boolean
        try {
            logger.info { "EXECUTOR: Executing step '${step.stepToolName}' (order=${step.order})" }

            val tool = mcpToolRegistry.byName(step.stepToolName)
            val stepContext = buildStepContext(plan)

            val result =
                tool.execute(
                    plan = plan,
                    taskDescription = step.stepInstruction,
                    stepContext = stepContext,
                )
            step.toolResult = result

            logger.info { "EXECUTOR: Step '${step.stepToolName}' completed with result type: ${result.javaClass.simpleName}" }

            when (result) {
                is ToolResult.Ok, is ToolResult.Ask -> {
                    step.status = StepStatusEnum.DONE
                    stepNotificationService.notifyStepCompleted(plan.id, plan.id, step)
                    planFailed = false
                }

                is ToolResult.Stop -> {
                    step.status = StepStatusEnum.FAILED
                    plan.status = PlanStatusEnum.FAILED
                    plan.finalAnswer = result.reason
                    planFailed = true
                    logger.info { "EXECUTOR: Plan stopped by tool: ${result.reason}" }
                    stepNotificationService.notifyStepCompleted(plan.id, plan.id, step)
                }

                is ToolResult.Error -> {
                    step.status = StepStatusEnum.FAILED
                    // Don't mark plan as FAILED - planner may resolve this with alternative steps
                    planFailed = false
                    logger.error { "EXECUTOR: Step '${step.stepToolName}' failed: ${result.errorMessage}" }
                    stepNotificationService.notifyStepCompleted(plan.id, plan.id, step)
                }

                else -> {
                    step.status = StepStatusEnum.FAILED
                    // Don't mark plan as FAILED - planner may resolve this with alternative steps
                    planFailed = false
                    logger.error { "EXECUTOR: Unsupported result type from step '${step.stepToolName}'" }
                    stepNotificationService.notifyStepCompleted(plan.id, plan.id, step)
                }
            }
        } catch (e: Exception) {
            logger.error(e) { "EXECUTOR: Exception executing step '${step.stepToolName}'" }
            step.toolResult = ToolResult.error("Step execution failed: ${e.message}")
            step.status = StepStatusEnum.FAILED
            plan.status = PlanStatusEnum.FAILED
            plan.finalAnswer = "Step execution failed: ${e.message}"
            planFailed = true
            stepNotificationService.notifyStepCompleted(plan.id, plan.id, step)
        }

        return planFailed
    }

    /**
     * Build context string from previous completed steps
     */
    private fun buildStepContext(plan: Plan): String {
        val completedSteps =
            plan.steps
                .filter { it.status == StepStatusEnum.DONE && it.toolResult != null }
                .sortedBy { it.order }

        if (completedSteps.isEmpty()) {
            return "No previous steps completed yet."
        }

        return buildString {
            appendLine("Previous completed steps:")
            completedSteps.forEach { step ->
                appendLine("- ${step.stepToolName}: ${summarizeToolResult(step.toolResult)}")
            }
        }
    }

    /**
     * Create a brief summary of a tool result for context
     */
    private fun summarizeToolResult(toolResult: ToolResult?): String = toolResult?.output ?: "No result"
}
