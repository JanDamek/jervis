package com.jervis.service.agent.execution

import com.jervis.domain.plan.PlanStatus
import com.jervis.domain.plan.StepStatus
import com.jervis.entity.mongo.PlanDocument
import com.jervis.repository.mongo.TaskContextMongoRepository
import com.jervis.service.mcp.McpToolRegistry
import mu.KotlinLogging
import org.springframework.stereotype.Service
import java.time.Instant

/**
 * Executes the first pending plan step using registered MCP tools.
 */
@Service
class PlanExecutorImpl(
    private val mcpToolRegistry: McpToolRegistry,
    private val taskContextRepo: TaskContextMongoRepository,
    private val ragIngestService: com.jervis.service.rag.RagIngestService,
) : PlanExecutor {
    private val logger = KotlinLogging.logger {}

    override suspend fun execute(plan: PlanDocument) {
        val now = Instant.now()
        if (plan.steps.isEmpty()) {
            logger.info { "EXECUTOR_NO_STEPS: plan=${plan.id} -> COMPLETED" }
            plan.status = PlanStatus.COMPLETED
            plan.updatedAt = now
            return
        }

        val idx = plan.steps.indexOfFirst { it.status == StepStatus.PENDING }
        if (idx < 0) {
            logger.info { "EXECUTOR_NO_PENDING: plan=${plan.id} -> COMPLETED" }
            plan.status = PlanStatus.COMPLETED
            plan.updatedAt = now
            return
        }

        val step = plan.steps[idx]
        val tool =
            mcpToolRegistry.byName(step.name)
                ?: run {
                    logger.error { "EXECUTOR_TOOL_MISSING: step='${step.name}' plan=${plan.id} -> FAILED" }
                    plan.status = PlanStatus.FAILED
                    plan.updatedAt = now
                    return
                }

        val context =
            taskContextRepo.findByContextId(plan.contextId)
                ?: run {
                    logger.error { "EXECUTOR_CONTEXT_MISSING: contextId=${plan.contextId} plan=${plan.id} -> FAILED" }
                    plan.status = PlanStatus.FAILED
                    plan.updatedAt = now
                    return
                }

        logger.info { "EXECUTOR_STEP_START: index=$idx step='${step.name}' plan=${plan.id}" }
        logger.debug { "EXECUTOR_STEP_TOOL: tool='${tool.name}', parameters=${step.parameters}" }

        val result =
            try {
                tool.execute(context = context, parameters = step.parameters)
            } catch (e: Exception) {
                logger.error(e) { "EXECUTOR_STEP_EXCEPTION: index=$idx step='${step.name}'" }
                plan.status = PlanStatus.FAILED
                plan.updatedAt = now
                return
            }

        logger.info { "EXECUTOR: Tool '${tool.name}' finished with output='${result.output.take(200)}'" }

        // Update context summary with the latest tool result and persist
        val updatedContext =
            context.copy(
                contextSummary = result.render().take(500),
                updatedAt = now,
            )
        runCatching { taskContextRepo.save(updatedContext) }
            .onSuccess { logger.info { "EXECUTOR_CONTEXT_SAVED: contextId=${updatedContext.contextId}" } }
            .onFailure { t -> logger.warn(t) { "EXECUTOR_CONTEXT_SAVE_FAIL: contextId=${updatedContext.contextId}" } }

        // Ingest step into RAG (best-effort)
        runCatching { ragIngestService.ingestStep(updatedContext, tool.name, step.parameters, result) }
            .onSuccess { pointId -> logger.info { "EXECUTOR_RAG_INGESTED: pointId=${pointId ?: "n/a"} tool='${tool.name}'" } }
            .onFailure { t -> logger.warn(t) { "EXECUTOR_RAG_INGEST_FAIL: tool='${tool.name}'" } }

        step.status = StepStatus.DONE
        step.output = result

        plan.updatedAt = now
    }
}
