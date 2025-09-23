package com.jervis.service.agent.execution

import com.jervis.configuration.prompts.PromptTypeEnum
import com.jervis.domain.context.TaskContext
import com.jervis.domain.plan.Plan
import com.jervis.domain.plan.PlanStatus
import com.jervis.domain.plan.StepStatus
import com.jervis.service.agent.context.TaskContextService
import com.jervis.service.agent.planner.Planner
import com.jervis.service.mcp.McpToolRegistry
import com.jervis.service.mcp.domain.ToolResult
import com.jervis.service.notification.StepNotificationService
import mu.KotlinLogging
import org.bson.types.ObjectId
import org.springframework.stereotype.Service
import java.time.Instant

/**
 * Executes all pending plan steps sequentially using registered MCP tools.
 */
@Service
class PlanExecutor(
    private val mcpToolRegistry: McpToolRegistry,
    private val taskContextService: TaskContextService,
    private val planner: Planner,
    private val stepNotificationService: StepNotificationService,
    private val treeExecutor: TreeExecutor,
) {
    private val logger = KotlinLogging.logger {}

    suspend fun execute(context: TaskContext) {
        context.plans.filter { it.status != PlanStatus.COMPLETED }.forEach { plan ->
            logger.debug { "EXECUTOR_PLAN_START: Processing planId=${plan.id}, status=${plan.status}, steps=${plan.steps.size}" }
            logger.debug {
                "EXECUTOR_PLAN_STEPS: ${plan.steps.map { "stepId=${it.id}, order=${it.order}, name=${it.name}, status=${it.status}" }}"
            }

            // Check if plan uses new tree-based execution model
            if (plan.executionTree.isNotEmpty()) {
                logger.info { "EXECUTOR_TREE_MODE: Using tree-based execution for plan ${plan.id} with ${plan.executionTree.size} root nodes" }
                plan.status = PlanStatus.RUNNING
                plan.updatedAt = Instant.now()
                taskContextService.save(context)
                
                // Use TreeExecutor for tree-based execution
                val treeExecutionSuccess = treeExecutor.executeTree(context, plan, plan.executionTree)
                
                plan.status = if (treeExecutionSuccess) PlanStatus.COMPLETED else PlanStatus.FAILED
                plan.updatedAt = Instant.now()
                taskContextService.save(context)
                
                stepNotificationService.notifyPlanStatusChanged(context.id, plan.id, plan.status)
                logger.info { "EXECUTOR_TREE_COMPLETE: Tree execution finished for plan ${plan.id}. Success: $treeExecutionSuccess" }
                return@forEach // Continue to next plan
            }
            
            // Legacy flat step execution for backward compatibility
            if (plan.steps.isEmpty()) {
                logger.error { "EXECUTOR_EMPTY_PLAN: Plan ${plan.id} has no steps - marking as FAILED instead of COMPLETED" }
                plan.status = PlanStatus.FAILED
                plan.finalAnswer = "Plan has no executable steps. This indicates a planner error."
                plan.updatedAt = Instant.now()
                taskContextService.save(context)
            } else {
                logger.info { "EXECUTOR_LEGACY_MODE: Using legacy step-based execution for plan ${plan.id} with ${plan.steps.size} steps" }
                plan.status = PlanStatus.RUNNING
                plan.updatedAt = Instant.now()
                taskContextService.save(context)
            }

            // Process steps by dependency level (stepBack) for tree-structured execution
            val pendingSteps = plan.steps.filter { it.status == StepStatus.PENDING }
            val stepsByLevel = pendingSteps.groupBy { it.stepBack }.toSortedMap()

            logger.debug { "EXECUTOR_TREE_EXECUTION: Processing ${stepsByLevel.size} dependency levels: ${stepsByLevel.keys}" }

            // Execute steps level by level based on stepBack dependencies
            for ((level, stepsAtLevel) in stepsByLevel) {
                logger.debug { "EXECUTOR_LEVEL_START: Processing ${stepsAtLevel.size} steps at dependency level $level" }

                // Execute steps at current level sequentially (parallel execution can be added later)
                for (step in stepsAtLevel.sortedBy { it.order }) {
                    try {
                        val tool = mcpToolRegistry.byName(PromptTypeEnum.valueOf(step.name))
                        logger.info { "EXECUTOR_STEP_START: stepId=${step.id} step='${step.name}' plan=${plan.id} level=$level" }
                        logger.debug {
                            "EXECUTOR_STEP_TOOL: tool='${tool.name}', taskDescription=${step.taskDescription}, " +
                                "stepBack=${step.stepBack}"
                        }

                        // Build step context if stepBack > 0
                        val stepContext =
                            if (step.stepBack > 0) {
                                buildStepContext(plan, step.stepBack)
                            } else {
                                ""
                            }

                        val result =
                            tool.execute(
                                context = context,
                                plan = plan,
                                taskDescription = step.taskDescription,
                                stepContext = stepContext,
                            )
                        logger.info { "EXECUTOR: Tool '${tool.name}' finished with output='${result.output.take(100)}'" }

                        when (result) {
                            is ToolResult.Ok, is ToolResult.Ask -> {
                                step.output = result
                                step.status = StepStatus.DONE
                                appendSummaryLine(plan, step.id, step.name, result)
                                stepNotificationService.notifyStepCompleted(context.id, plan.id, step)
                            }

                            is ToolResult.Stop -> {
                                step.output = result
                                step.status = StepStatus.FAILED
                                plan.status = PlanStatus.FAILED
                                plan.finalAnswer = result.reason
                                appendSummaryLine(plan, step.id, step.name, result)
                                logger.debug { "EXECUTOR_STOPPED: Plan execution halted due to unresolvable error: ${result.reason}" }
                                stepNotificationService.notifyStepCompleted(context.id, plan.id, step)
                                stepNotificationService.notifyPlanStatusChanged(context.id, plan.id, plan.status)
                                return@forEach // Exit plan execution
                            }

                            is ToolResult.InsertStep -> {
                                // Step insertion should not happen at tool execution level
                                // InsertStep results are only generated during recovery planning
                                logger.warn {
                                    "EXECUTOR_UNEXPECTED_INSERT_STEP: Received InsertStep result from tool '${tool.name}' - treating as error"
                                }
                                step.output = ToolResult.error("Unexpected step insertion request from tool execution")
                                step.status = StepStatus.FAILED
                                appendSummaryLine(plan, step.id, step.name, step.output)
                                stepNotificationService.notifyStepCompleted(context.id, plan.id, step)
                            }

                            is ToolResult.Error -> {
                                step.output = result
                                step.status = StepStatus.FAILED
                                val reason = result.errorMessage ?: "Unknown error"
                                appendSummaryLine(plan, step.id, step.name, result)
                                stepNotificationService.notifyStepCompleted(context.id, plan.id, step)

                                // Use recovery planning for step failures (replacing QuickPlanner)
                                logger.info {
                                    "EXECUTOR_RECOVERY_PLANNING: Step failure detected for '${step.name}', creating recovery plan"
                                }
                                try {
                                    val nextStep =
                                        plan.steps
                                            .filter { it.order > step.order && it.status == StepStatus.PENDING }
                                            .minByOrNull { it.order }

                                    val recoveryPlan = planner.createRecoveryPlan(context, plan, step, nextStep)

                                    if (recoveryPlan.steps.isNotEmpty()) {
                                        logger.info {
                                            "EXECUTOR_RECOVERY_SUCCESS: Recovery plan created with ${recoveryPlan.steps.size} alternative steps"
                                        }

                                        // Insert recovery steps before the failed step
                                        val stepsToReorder = plan.steps.filter { it.order >= step.order }.sortedBy { it.order }
                                        
                                        // Adjust orders for existing steps
                                        val orderOffset = recoveryPlan.steps.size
                                        stepsToReorder.forEach { it.order += orderOffset }

                                        // Insert recovery steps with proper ordering
                                        val recoverySteps = recoveryPlan.steps.mapIndexed { index, recoveryStep ->
                                            recoveryStep.copy(
                                                order = step.order + index,
                                                planId = plan.id,
                                                contextId = context.id
                                            )
                                        }

                                        plan.steps = (
                                            plan.steps.filter { it.order < step.order } +
                                            recoverySteps +
                                            stepsToReorder
                                        ).sortedBy { it.order }

                                        // Reset failed step status to pending for retry after recovery steps
                                        step.status = StepStatus.PENDING
                                        step.output = null

                                        logger.info {
                                            "EXECUTOR_RECOVERY_INSERTED: Inserted ${recoverySteps.size} recovery steps, " +
                                                "failed step '${step.name}' reset to PENDING for retry"
                                        }

                                        // Save changes and restart plan processing
                                        taskContextService.save(context)
                                        return@forEach // Exit to restart with new step structure
                                    } else {
                                        logger.debug { "EXECUTOR_RECOVERY_NO_STEPS: Recovery plan generated no steps" }
                                    }
                                } catch (e: Exception) {
                                    logger.error(e) { "EXECUTOR_RECOVERY_ERROR: Recovery planning failed" }
                                }

                                // Check for cycles - if same tool failed multiple times, stop
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
                                    return@forEach // Exit plan execution
                                }

                                // Trigger full replanning on error (fallback after recovery planning)
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
                                    return@forEach // Exit to reprocess with new steps
                                } catch (e: Exception) {
                                    logger.error(e) { "EXECUTOR_REPLANNING_FAILED: Falling back to marking plan as failed" }
                                    plan.status = PlanStatus.FAILED
                                    plan.finalAnswer = "Original error: $reason. Replanning failed: ${e.message}"
                                    stepNotificationService.notifyPlanStatusChanged(context.id, plan.id, plan.status)
                                    return@forEach // Exit plan execution
                                }
                            }
                        }

                        taskContextService.save(context)
                    } catch (e: Exception) {
                        logger.error(e) { "EXECUTOR_STEP_FAILED: Unexpected error executing step ${step.id}" }
                        step.status = StepStatus.FAILED
                        step.output = ToolResult.Error("Unexpected error: ${e.message}")
                        stepNotificationService.notifyStepCompleted(context.id, plan.id, step)
                        // Continue with next step instead of stopping the entire plan
                    }
                }
            }

            // Check if all steps are completed
            val remainingPendingSteps = plan.steps.filter { it.status == StepStatus.PENDING }
            val doneSteps = plan.steps.filter { it.status == StepStatus.DONE }
            val failedSteps = plan.steps.filter { it.status == StepStatus.FAILED }

            logger.debug {
                "EXECUTOR_PLAN_STATUS_CHECK: Plan ${plan.id} - pendingSteps=${remainingPendingSteps.size}, doneSteps=${doneSteps.size}, failedSteps=${failedSteps.size}"
            }

            if (remainingPendingSteps.isEmpty()) {
                if (doneSteps.isNotEmpty() || failedSteps.isNotEmpty()) {
                    logger.info {
                        "EXECUTOR_PLAN_COMPLETED: Plan ${plan.id} completed - all steps processed (done=${doneSteps.size}, failed=${failedSteps.size})"
                    }
                    plan.status = PlanStatus.COMPLETED
                    stepNotificationService.notifyPlanStatusChanged(context.id, plan.id, plan.status)
                } else {
                    logger.error { "EXECUTOR_NO_STEPS_PROCESSED: Plan ${plan.id} has no processed steps - this should not happen" }
                    plan.status = PlanStatus.FAILED
                    plan.finalAnswer = "No steps were processed. This indicates a planning or execution error."
                    stepNotificationService.notifyPlanStatusChanged(context.id, plan.id, plan.status)
                }
            }
            plan.updatedAt = Instant.now()
        }
        taskContextService.save(context)
    }

    private fun summarizeToolResult(toolResult: ToolResult?): String = when (toolResult) {
        is ToolResult.Ok -> toolResult.output.lineSequence().firstOrNull()?.take(200) ?: ""
        is ToolResult.Error -> toolResult.errorMessage ?: "Unknown error"
        is ToolResult.Ask -> toolResult.output.lineSequence().firstOrNull()?.take(200) ?: ""
        is ToolResult.Stop -> toolResult.reason
        is ToolResult.InsertStep -> "Insert step: ${toolResult.stepToInsert.name}"
        null -> "No output"
    }

    private fun buildStepContext(
        plan: Plan,
        stepBack: Int,
    ): String {
        val completedSteps =
            plan.steps
                .filter { it.status == StepStatus.DONE }
                .sortedBy { it.order }
                .takeLast(stepBack)

        return if (completedSteps.isEmpty()) {
            ""
        } else {
            buildString {
                appendLine("[CONTEXT_START]")
                completedSteps.forEach { step ->
                    val statusLabel = when (val output = step.output) {
                        is ToolResult.Ok -> "SUCCESS"
                        is ToolResult.Error -> "ERROR"
                        is ToolResult.Ask -> "ASK"
                        is ToolResult.Stop -> "STOPPED"
                        is ToolResult.InsertStep -> "STEP_INSERT"
                        null -> "NO_OUTPUT"
                    }

                    appendLine("[TOOL_RESPONSE name=${step.name} status=$statusLabel order=${step.order}]")
                    appendLine("TaskSummary: ${step.taskDescription.take(160)}")
                    when (val output = step.output) {
                        is ToolResult.Ok -> {
                            appendLine("Summary: ${summarizeToolResult(output)}")
                            appendLine("---")
                            appendLine(output.output)
                        }

                        is ToolResult.Error -> {
                            val msg = output.errorMessage ?: "Unknown error"
                            appendLine("Summary: $msg")
                            if (output.output.isNotBlank()) {
                                appendLine("---")
                                appendLine(output.output)
                            }
                        }

                        is ToolResult.Ask -> {
                            appendLine("Summary: ${summarizeToolResult(output)}")
                            appendLine("---")
                            appendLine(output.output)
                        }

                        is ToolResult.Stop -> {
                            appendLine("Summary: ${output.reason}")
                            if (output.output.isNotBlank()) {
                                appendLine("---")
                                appendLine(output.output)
                            }
                        }

                        is ToolResult.InsertStep -> {
                            appendLine("Summary: Insert step: ${output.stepToInsert.name}")
                            if (output.output.isNotBlank()) {
                                appendLine("---")
                                appendLine(output.output)
                            }
                        }

                        null -> {
                            appendLine("Summary: No output")
                        }
                    }
                    appendLine("[/TOOL_RESPONSE]")
                    appendLine()
                }
                appendLine("[/CONTEXT_END]")
            }
        }
    }

    private fun appendSummaryLine(
        plan: Plan,
        stepId: ObjectId,
        toolName: String,
        toolResult: ToolResult?,
    ) {
        val summary = summarizeToolResult(toolResult)
        val truncatedSummary =
            if (toolName == "RAG_QUERY") {
                summary // Preserve full RAG results for proper context
            } else {
                summary.take(2000) // Increased limit for better context, but still bounded
            }

        val line = "Step $stepId: $toolName → $truncatedSummary"
        val prefix = plan.contextSummary?.takeIf { it.isNotBlank() }?.plus("\n") ?: ""
        plan.contextSummary = prefix + line
    }
}
