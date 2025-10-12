package com.jervis.service.agent.execution

import com.jervis.domain.context.TaskContext
import com.jervis.domain.plan.Plan
import com.jervis.domain.plan.PlanStatus
import com.jervis.domain.plan.PlanStep
import com.jervis.domain.plan.StepStatus
import com.jervis.service.agent.context.TaskContextService
import com.jervis.service.mcp.McpToolRegistry
import com.jervis.service.mcp.domain.ToolResult
import com.jervis.service.notification.StepNotificationService
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import mu.KotlinLogging
import org.springframework.stereotype.Service
import java.time.Instant

/**
 * Executor that processes plan steps and manages task resolution checking.
 * Supports concurrent execution of multiple plans within a context.
 * Each plan is executed independently with its own steps processed sequentially.
 * Handles step execution, plan status updates, and creation of additional plans for missing requirements.
 */
@Service
class PlanExecutor(
    private val mcpToolRegistry: McpToolRegistry,
    private val taskContextService: TaskContextService,
    private val stepNotificationService: StepNotificationService,
) {
    private val logger = KotlinLogging.logger {}

    suspend fun execute(context: TaskContext) {
        // Process each plan that is not yet completed or failed concurrently
        val activePlans =
            context.plans
                .filter { it.status !in listOf(PlanStatus.COMPLETED, PlanStatus.FAILED) }

        if (activePlans.isEmpty()) return

        coroutineScope {
            val jobs =
                activePlans.map { plan ->
                    async {
                        executePlan(context, plan)
                    }
                }
            jobs.forEach { it.await() }
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

        pendingSteps.forEach { step ->
            executeOneStep(step, plan, context)
        }

        plan.updatedAt = Instant.now()
        taskContextService.save(context)
        stepNotificationService.notifyPlanStatusChanged(context.id, plan.id, plan.status)

        logger.info { "EXECUTOR: Plan execution finished - planId=${plan.id}, status=${plan.status}" }
    }

    suspend fun executeOneStep(
        step: PlanStep,
        plan: Plan,
        context: TaskContext,
    ): Boolean {
        var planFailed: Boolean
        try {
            logger.info { "EXECUTOR: Executing step '${step.stepToolName}' (order=${step.order})" }

            val tool = mcpToolRegistry.byName(step.stepToolName)
            val stepContext = buildStepContext(plan)

            val result =
                tool.execute(
                    context = context,
                    plan = plan,
                    taskDescription = step.stepInstruction,
                    stepContext = stepContext,
                )
            step.toolResult = result

            logger.info { "EXECUTOR: Step '${step.stepToolName}' completed with result type: ${result.javaClass.simpleName}" }

            when (result) {
                is ToolResult.Ok, is ToolResult.Ask -> {
                    step.status = StepStatus.DONE
                    stepNotificationService.notifyStepCompleted(context.id, plan.id, step)
                    planFailed = false
                }

                is ToolResult.Stop -> {
                    step.status = StepStatus.FAILED
                    plan.status = PlanStatus.FAILED
                    plan.finalAnswer = result.reason
                    planFailed = true
                    logger.info { "EXECUTOR: Plan stopped by tool: ${result.reason}" }
                    stepNotificationService.notifyStepCompleted(context.id, plan.id, step)
                }

                is ToolResult.Error -> {
                    step.status = StepStatus.FAILED
                    // Don't mark plan as FAILED - planner may resolve this with alternative steps
                    planFailed = false
                    logger.error { "EXECUTOR: Step '${step.stepToolName}' failed: ${result.errorMessage}" }
                    stepNotificationService.notifyStepCompleted(context.id, plan.id, step)
                }

                else -> {
                    step.status = StepStatus.FAILED
                    // Don't mark plan as FAILED - planner may resolve this with alternative steps
                    planFailed = false
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

        return planFailed
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
            is ToolResult.Ok -> toolResult.output
            is ToolResult.Ask -> "Asked user: ${toolResult.output}"
            is ToolResult.Error -> "Error: ${toolResult.errorMessage}"
            is ToolResult.Stop -> "Stopped: ${toolResult.reason}"
            else -> "Unknown result: ${toolResult?.output}"
        }
}
