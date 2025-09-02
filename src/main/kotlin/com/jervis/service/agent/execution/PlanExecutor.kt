package com.jervis.service.agent.execution

import com.jervis.domain.context.TaskContext
import com.jervis.domain.plan.Plan
import com.jervis.domain.plan.PlanStatus
import com.jervis.domain.plan.StepStatus
import com.jervis.service.agent.context.TaskContextService
import com.jervis.service.agent.planner.Planner
import com.jervis.service.mcp.McpToolRegistry
import com.jervis.service.mcp.domain.ToolResult
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
) {
    private val logger = KotlinLogging.logger {}

    suspend fun execute(context: TaskContext) {
        context.plans.filter { it.status != PlanStatus.COMPLETED }.forEach { plan ->
            plan.status = PlanStatus.RUNNING
            plan.updatedAt = Instant.now()
            taskContextService.save(context)

            try {
                plan.steps.sortedBy { it.order }.first { it.status == StepStatus.PENDING }.let { step ->
                    mcpToolRegistry.byName(step.name)?.let { tool ->
                        logger.info { "EXECUTOR_STEP_START: stepId=${step.id} step='${step.name}' plan=${plan.id}" }
                        logger.debug { "EXECUTOR_STEP_TOOL: tool='${tool.name}', taskDescription=${step.taskDescription}" }

                        val result = tool.execute(context = context, plan, taskDescription = step.taskDescription)
                        logger.info { "EXECUTOR: Tool '${tool.name}' finished with output='${result.output.take(200)}'" }

                        when (result) {
                            is ToolResult.Ok, is ToolResult.Ask -> {
                                step.output = result
                                step.status = StepStatus.DONE
                                appendSummaryLine(plan, step.id, step.name, result.output)
                            }

                            is ToolResult.Stop -> {
                                step.output = result
                                step.status = StepStatus.FAILED
                                plan.status = PlanStatus.FAILED
                                plan.failureReason = result.reason
                                appendSummaryLine(plan, step.id, step.name, "STOPPED: ${result.reason}")
                                logger.info { "EXECUTOR_STOPPED: Plan execution halted due to unresolvable error: ${result.reason}" }
                            }

                            is ToolResult.Error -> {
                                step.output = result
                                step.status = StepStatus.FAILED
                                val reason = result.errorMessage ?: "Unknown error"
                                appendSummaryLine(plan, step.id, step.name, "ERROR: $reason")

                                // Check for cycles - if same tool failed multiple times with similar parameters, stop
                                val failedStepsWithSameTool =
                                    plan.steps.filter {
                                        it.status == StepStatus.FAILED && it.name == step.name
                                    }
                                if (failedStepsWithSameTool.size >= 3) {
                                    plan.status = PlanStatus.FAILED
                                    plan.failureReason =
                                        "Cycle detected: Tool '${step.name}' failed ${failedStepsWithSameTool.size} times. Stopping to prevent infinite loop."
                                    logger.info {
                                        "EXECUTOR_CYCLE_DETECTED: Tool '${step.name}' failed ${failedStepsWithSameTool.size} times, stopping plan execution"
                                    }
                                    return@forEach
                                }

                                // Trigger replanning instead of failing
                                logger.info { "EXECUTOR_REPLANNING: Triggering replanning due to error in step '${step.name}': $reason" }
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
                                    logger.info {
                                        "EXECUTOR_REPLANNING_SUCCESS: Plan replanned - preserved ${completedSteps.size} completed steps, ${failedSteps.size} failed steps, added ${newSteps.size} new steps"
                                    }
                                } catch (e: Exception) {
                                    logger.error(e) { "EXECUTOR_REPLANNING_FAILED: Falling back to marking plan as failed" }
                                    plan.status = PlanStatus.FAILED
                                    plan.failureReason = "Original error: $reason. Replanning failed: ${e.message}"
                                }
                            }
                        }

                        ragIngestService.ingestStep(context, plan, tool.name, step.taskDescription, result)
                        taskContextService.save(context)
                    }
                }
            } catch (_: NoSuchElementException) {
                plan.status = PlanStatus.COMPLETED
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
