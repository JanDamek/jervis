package com.jervis.orchestrator.tools

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import com.jervis.entity.TaskDocument
import com.jervis.orchestrator.agents.ContextAgent
import com.jervis.orchestrator.agents.IngestAgent
import com.jervis.orchestrator.agents.PlannerAgent
import com.jervis.orchestrator.agents.ResearchAgent
import com.jervis.orchestrator.agents.ReviewerAgent
import com.jervis.orchestrator.agents.SolutionArchitectAgent
import com.jervis.orchestrator.model.ContextPack
import com.jervis.orchestrator.model.EvidencePack
import com.jervis.orchestrator.model.PlanStep
import kotlinx.serialization.json.Json
import mu.KotlinLogging

/**
 * Internal orchestrator tools wrapping internal agents.
 *
 * Internal agents use Koog's retry/validation patterns - NO silent fallbacks.
 * If LLM produces invalid output → Koog automatically retries with error message.
 * Koog framework handles serialization automatically.
 */
class InternalAgentTools(
    private val task: TaskDocument,
    private val contextAgent: ContextAgent,
    private val plannerAgent: PlannerAgent,
    private val researchAgent: ResearchAgent,
    private val reviewerAgent: ReviewerAgent,
    private val ingestAgent: IngestAgent,
    private val solutionArchitectAgent: SolutionArchitectAgent,
) : ToolSet {
    companion object {
        private val logger = KotlinLogging.logger {}
        private val json = Json { prettyPrint = true }
    }

    @Tool
    @LLMDescription(
        "Get mandatory context for task execution. Always call this first. " +
            "Returns: projectPath, build/test commands, known facts, and missingInfo.",
    )
    suspend fun getContext(
        @LLMDescription("Original user request / query that the orchestrator is processing")
        userQuery: String,
    ): String {
        logger.info { "TOOL_getContext | correlationId=${task.correlationId}" }
        val context = contextAgent.run(task = task, userQuery = userQuery)
        return json.encodeToString(context)
    }

    @Tool
    @LLMDescription(
        "Create execution plan for the query. Call after getContext. " +
            "Returns: OrderedPlan with sequential steps to execute.",
    )
    suspend fun createPlan(
        @LLMDescription("Original user query to decompose into steps")
        userQuery: String,
        @LLMDescription("Context JSON from getContext()")
        contextJson: String,
        @LLMDescription("Optional evidence JSON from previous iteration (omit on first call)")
        evidenceJson: String? = null,
    ): String {
        logger.info { "TOOL_createPlan | correlationId=${task.correlationId}" }
        val context = json.decodeFromString<ContextPack>(contextJson)
        val evidence = evidenceJson?.let { json.decodeFromString<EvidencePack>(it) }
        val plan = plannerAgent.run(task = task, userQuery = userQuery, context = context, evidence = evidence)
        return json.encodeToString(plan)
    }

    @Tool
    @LLMDescription(
        "Gather evidence for a research question using available tools (RAG, GraphDB). " +
            "Returns: EvidencePack with collected items and summary.",
    )
    suspend fun gatherEvidence(
        @LLMDescription("What to research / what information is needed")
        researchQuestion: String,
        @LLMDescription("Context JSON from getContext()")
        contextJson: String,
    ): String {
        logger.info { "TOOL_gatherEvidence | correlationId=${task.correlationId}" }
        val context = json.decodeFromString<ContextPack>(contextJson)
        val evidence = researchAgent.run(task = task, researchQuestion = researchQuestion, context = context)
        return json.encodeToString(evidence)
    }

    @Tool
    @LLMDescription(
        "Review completeness of executed plan. Call after executing all steps. " +
            "Returns: ReviewResult indicating if complete or if iteration needed.",
    )
    suspend fun reviewCompleteness(
        @LLMDescription("Original user query to check completeness against")
        originalQuery: String,
        @LLMDescription("JSON array of executed steps")
        executedStepsJson: String,
        @LLMDescription("Evidence JSON collected during execution")
        evidenceJson: String,
        @LLMDescription("Current iteration number (0, 1, 2, ...)")
        iteration: Int,
        @LLMDescription("Maximum allowed iterations (usually 3)")
        maxIterations: Int,
    ): String {
        logger.info { "TOOL_reviewCompleteness | correlationId=${task.correlationId} | iteration=$iteration/$maxIterations" }
        val executedSteps = json.decodeFromString<List<PlanStep>>(executedStepsJson)
        val evidence = json.decodeFromString<EvidencePack>(evidenceJson)
        val review =
            reviewerAgent.run(
                task = task,
                originalQuery = originalQuery,
                executedSteps = executedSteps,
                evidence = evidence,
                currentIteration = iteration,
                maxIterations = maxIterations,
            )
        return json.encodeToString(review)
    }

    @Tool
    @LLMDescription(
        "Ingest information into RAG and GraphDB for long-term storage. " +
            "Returns: IngestResult with summary of indexed facts.",
    )
    suspend fun ingestKnowledge(
        @LLMDescription("Facts or analysis results to be indexed")
        informationToIngest: String,
    ): String {
        logger.info { "TOOL_ingestKnowledge | correlationId=${task.correlationId}" }
        val result = ingestAgent.run(task = task, informationToIngest = informationToIngest)
        return json.encodeToString(result)
    }

    @Tool
    @LLMDescription(
        "Propose technical specification for a plan step. MANDATORY before any coding/delegation. " +
            "Returns: A2ADelegationSpec with target agent, files and instructions.",
    )
    suspend fun proposeTechnicalSpecification(
        @LLMDescription("The current plan step being architected")
        planStepJson: String,
        @LLMDescription("Context JSON from getContext()")
        contextJson: String,
        @LLMDescription("Evidence JSON from gatherEvidence() or previous steps")
        evidenceJson: String,
    ): String {
        logger.info { "TOOL_proposeTechnicalSpecification | correlationId=${task.correlationId}" }
        val step = json.decodeFromString<PlanStep>(planStepJson)
        val context = json.decodeFromString<ContextPack>(contextJson)
        val evidence = json.decodeFromString<EvidencePack>(evidenceJson)
        val spec = solutionArchitectAgent.run(task = task, context = context, evidence = evidence, step = step)
        logger.info { "TOOL_proposeTechnicalSpecification | result=${spec.agent}" }
        return json.encodeToString(spec)
    }
}
