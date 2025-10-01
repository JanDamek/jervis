package com.jervis.service.agent.execution

import com.jervis.configuration.prompts.PromptTypeEnum
import com.jervis.domain.context.TaskContext
import com.jervis.domain.plan.Plan
import com.jervis.domain.plan.PlanStatus
import com.jervis.domain.plan.StepStatus
import com.jervis.service.agent.context.TaskContextService
import com.jervis.service.mcp.McpToolRegistry
import com.jervis.service.mcp.domain.ToolResult
import com.jervis.service.notification.StepNotificationService
import mu.KotlinLogging
import org.springframework.stereotype.Service
import java.time.Instant

/**
 * Simple flat executor that processes plan steps sequentially by order.
 * Removed complex recovery planning, tree execution, and step insertion logic.
 */
@Service
class PlanExecutor(
    private val mcpToolRegistry: McpToolRegistry,
    private val taskContextService: TaskContextService,
    private val stepNotificationService: StepNotificationService,
) {
    private val logger = KotlinLogging.logger {}

    suspend fun execute(context: TaskContext) {
        // Process each plan that is not yet completed or failed
        context.plans
            .filter { it.status !in listOf(PlanStatus.COMPLETED, PlanStatus.FAILED) }
            .forEach { plan ->
                executePlan(context, plan)
            }
    }

    private suspend fun executePlan(
        context: TaskContext,
        plan: Plan,
    ) {
        logger.info { "EXECUTOR: Starting plan execution - planId=${plan.id}, steps=${plan.steps.size}" }

        if (plan.steps.isEmpty()) {
            logger.warn { "EXECUTOR: Plan ${plan.id} has no steps - marking as FAILED" }
            plan.status = PlanStatus.FAILED
            plan.finalAnswer = "Plan has no executable steps"
            plan.updatedAt = Instant.now()
            taskContextService.save(context)
            stepNotificationService.notifyPlanStatusChanged(context.id, plan.id, plan.status)
            return
        }

        plan.status = PlanStatus.RUNNING
        plan.updatedAt = Instant.now()
        taskContextService.save(context)
        stepNotificationService.notifyPlanStatusChanged(context.id, plan.id, plan.status)

        // Get pending steps sorted by order for flat sequential execution
        val pendingSteps =
            plan.steps
                .filter { it.status == StepStatus.PENDING }
                .sortedBy { it.order }

        logger.info { "EXECUTOR: Processing ${pendingSteps.size} pending steps" }

        var planFailed = false

        for (step in pendingSteps) {
            if (planFailed) break

            try {
                logger.info { "EXECUTOR: Executing step '${step.stepToolName}' (order=${step.order})" }

                val tool = mcpToolRegistry.byName(PromptTypeEnum.valueOf(step.stepToolName))
                val stepContext = buildStepContext(plan)

                val result =
                    tool.execute(
                        context = context,
                        plan = plan,
                        taskDescription = step.stepInstruction,
                        stepContext = stepContext,
                    )

                logger.info { "EXECUTOR: Step '${step.stepToolName}' completed with result type: ${result.javaClass.simpleName}" }

                when (result) {
                    is ToolResult.Ok, is ToolResult.Ask -> {
                        step.toolResult = result
                        step.status = StepStatus.DONE
                        stepNotificationService.notifyStepCompleted(context.id, plan.id, step)
                    }

                    is ToolResult.Stop -> {
                        step.toolResult = result
                        step.status = StepStatus.FAILED
                        plan.status = PlanStatus.FAILED
                        plan.finalAnswer = result.reason
                        planFailed = true
                        logger.info { "EXECUTOR: Plan stopped by tool: ${result.reason}" }
                        stepNotificationService.notifyStepCompleted(context.id, plan.id, step)
                    }

                    is ToolResult.Error -> {
                        step.toolResult = result
                        step.status = StepStatus.FAILED
                        plan.status = PlanStatus.FAILED
                        plan.finalAnswer = "Step failed: ${result.errorMessage ?: "Unknown error"}"
                        planFailed = true
                        logger.error { "EXECUTOR: Step '${step.stepToolName}' failed: ${result.errorMessage}" }
                        stepNotificationService.notifyStepCompleted(context.id, plan.id, step)
                    }

                    else -> {
                        // Handle any other result types as errors
                        step.toolResult =
                            ToolResult.error("Unsupported tool result type: ${result.javaClass.simpleName}")
                        step.status = StepStatus.FAILED
                        plan.status = PlanStatus.FAILED
                        plan.finalAnswer = "Unsupported tool result"
                        planFailed = true
                        logger.error { "EXECUTOR: Unsupported result type from step '${step.stepToolName}'" }
                        stepNotificationService.notifyStepCompleted(context.id, plan.id, step)
                    }
                }
            } catch (e: Exception) {
                logger.error(e) { "EXECUTOR: Exception executing step '${step.stepToolName}'" }
                step.toolResult = ToolResult.error("Step execution failed: ${e.message}")
                step.status = StepStatus.FAILED
                plan.status = PlanStatus.FAILED
                plan.finalAnswer = "Step execution failed: ${e.message}"
                planFailed = true
                stepNotificationService.notifyStepCompleted(context.id, plan.id, step)
            }

            // Save progress after each step
            plan.updatedAt = Instant.now()
            taskContextService.save(context)
        }

        // Final plan status update
        if (!planFailed) {
            val allStepsCompleted = plan.steps.all { it.status == StepStatus.DONE }
            if (allStepsCompleted) {
                plan.status = PlanStatus.COMPLETED
                logger.info { "EXECUTOR: Plan ${plan.id} completed successfully" }
            } else {
                plan.status = PlanStatus.FAILED
                plan.finalAnswer = "Not all steps completed"
                logger.warn { "EXECUTOR: Plan ${plan.id} has incomplete steps" }
            }
        }

        plan.updatedAt = Instant.now()
        taskContextService.save(context)
        stepNotificationService.notifyPlanStatusChanged(context.id, plan.id, plan.status)

        logger.info { "EXECUTOR: Plan execution finished - planId=${plan.id}, status=${plan.status}" }
    }

    /**
     * Build context string from previous completed steps
     */
    private fun buildStepContext(plan: Plan): String {
        val completedSteps =
            plan.steps
                .filter { it.status == StepStatus.DONE && it.toolResult != null }
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
    private fun summarizeToolResult(toolResult: ToolResult?): String =
        when (toolResult) {
            is ToolResult.Ok -> toolResult.output.take(200) + if (toolResult.output.length > 200) "..." else ""
            is ToolResult.Ask -> "Asked user: ${toolResult.output.take(100)}"
            is ToolResult.Error -> "Error: ${toolResult.errorMessage}"
            is ToolResult.Stop -> "Stopped: ${toolResult.reason}"
            else -> "Unknown result"
        }
}
