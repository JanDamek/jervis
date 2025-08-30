package com.jervis.service.agent.execution

import com.jervis.domain.plan.PlanStatus
import com.jervis.domain.plan.StepStatus
import com.jervis.entity.mongo.TaskContextDocument
import com.jervis.repository.mongo.PlanStepMongoRepository
import com.jervis.service.agent.context.TaskContextService
import com.jervis.service.mcp.McpToolRegistry
import com.jervis.service.mcp.domain.ToolResult
import com.jervis.service.rag.RagIngestService
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
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
    private val planStepMongoRepository: PlanStepMongoRepository,
) {
    private val logger = KotlinLogging.logger {}

    suspend fun execute(context: TaskContextDocument) {
        context.plans.collect { plan ->
            plan.status = PlanStatus.RUNNING
            plan.updatedAt = Instant.now()
            taskContextService.save(context)

            try {
                plan.steps.filter { it.status == StepStatus.PENDING }.first().let { step ->
                    mcpToolRegistry.byName(step.name)?.let { tool ->
                        logger.info { "EXECUTOR_STEP_START: stepId=${step.id} step='${step.name}' plan=${plan.id}" }
                        logger.debug { "EXECUTOR_STEP_TOOL: tool='${tool.name}', parameters=${step.parameters}" }

                        val result = tool.execute(context = context, parameters = step.parameters)
                        logger.info { "EXECUTOR: Tool '${tool.name}' finished with output='${result.output.take(200)}'" }

                        when (result) {
                            is ToolResult.Ok, is ToolResult.Ask -> {
                                step.output = result
                                step.status = StepStatus.DONE
                                appendSummaryLine(context, step.id, step.name, result.output)
                            }

                            is ToolResult.Error -> {
                                step.output = result
                                plan.status = PlanStatus.FAILED
                                val reason = result.errorMessage ?: "Unknown error"
                                context.failureReason = reason
                                appendSummaryLine(context, step.id, step.name, "ERROR: $reason")
                            }

                            is ToolResult.Deferred -> {
                                result.requiredStep.planId = plan.id
                                result.requiredStep.contextId = context.id
                                result.requiredStep.status = StepStatus.PENDING
                                planStepMongoRepository.save(result.requiredStep)
                                step.output = result
                                appendSummaryLine(
                                    context,
                                    step.id,
                                    step.name,
                                    "DEFER: ${result.output} -> inject '${result.requiredStep.name}'",
                                )
                            }
                        }

                        ragIngestService.ingestStep(context, tool.name, step.parameters, result)
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
        context: TaskContextDocument,
        stepId: ObjectId,
        toolName: String,
        message: String,
    ) {
        val line = "Step $stepId: $toolName → ${message.take(1000)}"
        val prefix = context.contextSummary?.takeIf { it.isNotBlank() }?.plus("\n") ?: ""
        context.contextSummary = prefix + line
    }
}
