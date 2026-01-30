package com.jervis.orchestrator.tools

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import com.jervis.entity.TaskDocument
import com.jervis.koog.qualifier.KoogQualifierAgent
import com.jervis.orchestrator.agents.CodeMapper
import com.jervis.orchestrator.agents.ContextAgent
import com.jervis.orchestrator.agents.EvidenceCollector
import com.jervis.orchestrator.agents.InterpreterAgent
import com.jervis.orchestrator.agents.MemoryRetriever
import com.jervis.orchestrator.agents.PlannerAgent
import com.jervis.orchestrator.agents.ProgramManager
import com.jervis.orchestrator.agents.ResearchAgent
import com.jervis.orchestrator.agents.ReviewerAgent
import com.jervis.orchestrator.agents.SolutionArchitectAgent
import com.jervis.orchestrator.agents.WorkflowAnalyzer
import com.jervis.orchestrator.model.ContextPack
import com.jervis.orchestrator.model.EvidencePack
import com.jervis.orchestrator.model.OrderedPlan
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
    private val qualifierAgent: KoogQualifierAgent,
    private val solutionArchitectAgent: SolutionArchitectAgent,
    private val interpreterAgent: InterpreterAgent,
    private val workflowAnalyzer: WorkflowAnalyzer,
    private val programManager: ProgramManager,
    private val memoryRetriever: MemoryRetriever,
    private val evidenceCollector: EvidenceCollector,
    private val codeMapper: CodeMapper,
) : ToolSet {
    companion object {
        private val logger = KotlinLogging.logger {}
        private val json = Json { prettyPrint = true }
    }

    @Tool
    @LLMDescription(
        """Analyze and interpret user request to understand the high-level goal.
        Use this ONLY for EPIC/PROGRAM planning or complex work item management tasks.

        For SEARCH/ANALYSIS tasks (like "find X in knowledge base"), you do NOT need this tool.
        Instead, directly use getContext() → gatherEvidence() → compose answer.

        Returns: NormalizedRequest JSON with:
        - type: REQUEST_TYPE (CODE_CHANGE, CODE_ANALYSIS, ADVICE, etc.)
        - goals: list of normalized goals
        - entities: mentioned entities (tickets, files, etc.)
        - outcome: expected outcome description
        """
    )
    suspend fun interpretRequest(): String {
        logger.info { "🔧 TOOL_CALL | tool=interpretRequest | correlationId=${task.correlationId}" }
        val request = interpreterAgent.run(task = convertTask(task))
        logger.info { "✓ TOOL_RESULT | tool=interpretRequest | goals=${request.goals.size} | correlationId=${task.correlationId}" }
        return json.encodeToString(request)
    }

    @Tool
    @LLMDescription(
        """Analyze Jira/issue tracker workflow definition and map states to execution phases.

        Use this when planning EPIC or BACKLOG_PROGRAM work that involves issue tracker integration.
        Analyzes workflow JSON to understand state transitions, groups states into phases (todo/in-progress/done),
        and provides mapping for automated issue management.

        Returns: WorkflowMapping JSON with:
        - stateGroups: States grouped by phase (TODO, IN_PROGRESS, DONE, BLOCKED)
        - transitions: Allowed state transitions
        - readinessChecks: Pre-conditions for state transitions

        Example: analyzeWorkflow(jiraWorkflowJson) before planning epic implementation
        """
    )
    suspend fun analyzeWorkflow(
        @LLMDescription("Workflow definition JSON from issue tracker (Jira, GitHub, etc.)")
        workflowDefinitionJson: String
    ): String {
        logger.info { "TOOL_analyzeWorkflow | correlationId=${task.correlationId}" }
        // Potřebujeme převést entity.TaskDocument na model.TaskDocument pro agenta
        // Zjednodušeno pro demo
        val mapping = workflowAnalyzer.run(task = convertTask(task), workflowDefinitionJson = workflowDefinitionJson)
        return json.encodeToString(mapping)
    }

    @Tool
    @LLMDescription(
        """Create comprehensive execution plan for implementing a Jira EPIC.

        Use this for EPIC request type - breaks down epic into waves of work items with:
        - Dependency analysis between tasks
        - Resource requirements
        - Readiness assessment for each wave
        - Risk identification
        - Timeline estimation

        Returns: ProgramState JSON with:
        - waves: Execution waves with grouped tasks
        - tasks: Individual work items with dependencies
        - readinessReports: Pre-flight checks for each wave
        - riskAssessment: Identified risks and mitigation strategies
        - timeline: Estimated duration and milestones

        Example: planEpic(epicId="JERVIS-42") to create implementation plan
        """
    )
    suspend fun planEpic(
        @LLMDescription("Jira epic identifier (e.g., 'PROJ-123', 'EPIC-456')")
        epicId: String
    ): String {
        logger.info { "TOOL_planEpic | epicId=$epicId, correlationId=${task.correlationId}" }
        val state = programManager.planEpic(clientId = task.clientId, epicId = epicId)
        return json.encodeToString(state)
    }

    private fun convertTask(task: com.jervis.entity.TaskDocument): com.jervis.orchestrator.model.TaskDocument =
        com.jervis.orchestrator.model.TaskDocument(
            id = task.id.toString(),
            clientId = task.clientId.toString(),
            projectId = task.projectId?.toString(),
            type = task.type.name,
            content = task.content,
            state = task.state.name,
            correlationId = task.correlationId,
            sourceUrn = task.sourceUrn.toString(),
            createdAt = task.createdAt.toString(),
            rollingSummary = "",
        )

    @Tool
    @LLMDescription(
        """Get mandatory context for task execution - ALWAYS call this FIRST before doing anything else.

        REQUIRED: You MUST provide the userQuery parameter with the user's original request text.

        Example call: getContext(userQuery="prohledej historii nákupu v Alze")

        Returns: ContextPack JSON with:
        - projectPath: absolute path to project (e.g., /workspace/clients/.../projects/.../git)
        - buildCommand: how to build the project (if applicable)
        - testCommand: how to run tests (if applicable)
        - knownFacts: list of known facts about the project from knowledge base
        - missingInfo: list of information gaps that need research
        """
    )
    suspend fun getContext(
        @LLMDescription("The user's original request/query text - REQUIRED parameter, cannot be omitted")
        userQuery: String,
    ): String {
        logger.info { "🔧 TOOL_CALL | tool=getContext | userQuery='${userQuery.take(100)}' | correlationId=${task.correlationId}" }

        if (userQuery.isBlank()) {
            val error = "ERROR: userQuery parameter is required and cannot be blank. Please provide the user's original request."
            logger.error { "❌ TOOL_CALL_INVALID | tool=getContext | error=$error | correlationId=${task.correlationId}" }
            return """{"error": "$error"}"""
        }

        val context = contextAgent.run(task = task, userQuery = userQuery)
        logger.info { "✓ TOOL_RESULT | tool=getContext | projectPath=${context.projectPath} | correlationId=${task.correlationId}" }
        return json.encodeToString(context)
    }

    @Tool
    @LLMDescription(
        "Create or refine an execution plan for the query. " +
            "Returns: OrderedPlan with sequential steps to execute.",
    )
    suspend fun createPlan(
        @LLMDescription("Original user query to decompose into steps")
        userQuery: String,
        @LLMDescription("Context JSON from getContext()")
        contextJson: String,
        @LLMDescription("Optional evidence JSON from previous iteration (omit on first call)")
        evidenceJson: String? = null,
        @LLMDescription("Optional current plan JSON if refining an existing plan")
        currentPlanJson: String? = null,
    ): String {
        logger.info { "🔧 TOOL_CALL | tool=createPlan | correlationId=${task.correlationId}" }
        val context = json.decodeFromString<ContextPack>(contextJson)
        val evidence = evidenceJson?.let { json.decodeFromString<EvidencePack>(it) }
        val existingPlan = currentPlanJson?.let { json.decodeFromString<OrderedPlan>(it) }
        val plan =
            plannerAgent.run(
                task = task,
                userQuery = userQuery,
                context = context,
                evidence = evidence,
                existingPlan = existingPlan,
            )
        logger.info { "✓ TOOL_RESULT | tool=createPlan | steps=${plan.steps.size} | correlationId=${task.correlationId}" }
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
        logger.info { "🔧 TOOL_CALL | tool=gatherEvidence | question=$researchQuestion | correlationId=${task.correlationId}" }
        val context = json.decodeFromString<ContextPack>(contextJson)
        val evidence = researchAgent.run(task = task, researchQuestion = researchQuestion, context = context)
        logger.info { "✓ TOOL_RESULT | tool=gatherEvidence | items=${evidence.items.size} | correlationId=${task.correlationId}" }
        return json.encodeToString(evidence)
    }

    @Tool
    @LLMDescription(
        "Review completeness of executed plan. " +
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
            "Returns: OK if successful.",
    )
    suspend fun ingestKnowledge(
        @LLMDescription("Facts or analysis results to be indexed")
        informationToIngest: String,
    ): String {
        logger.info { "TOOL_ingestKnowledge | correlationId=${task.correlationId}" }
        // Adapt input to TaskDocument for qualifier agent
        return qualifierAgent.run(task.copy(content = informationToIngest)) { msg, meta ->
            logger.info { "QUALIFIER_PROGRESS: $msg | $meta" }
        }
    }

    @Tool
    @LLMDescription(
        "Retrieve relevant evidence from Long-Term Memory (RAG + GraphDB). " +
            "Returns: EvidencePack with collected items and summary.",
    )
    suspend fun retrieveFromMemory(query: String): String {
        logger.info { "TOOL_retrieveFromMemory | correlationId=${task.correlationId}" }
        val evidence = memoryRetriever.run(task = convertTask(task), query = query)
        return json.encodeToString(evidence)
    }

    @Tool
    @LLMDescription(
        "Collect detailed evidence from external trackers (Jira, Confluence, Email). " +
            "Returns: EvidencePack with collected items and summary.",
    )
    suspend fun collectEvidence(targets: String): String {
        logger.info { "TOOL_collectEvidence | correlationId=${task.correlationId}" }
        val evidence = evidenceCollector.run(task = convertTask(task), targets = targets)
        return json.encodeToString(evidence)
    }

    @Tool
    @LLMDescription(
        "Identify relevant code entrypoints, modules, and files. " +
            "Returns: CodeMapSummary.",
    )
    suspend fun mapCode(goal: String): String {
        logger.info { "TOOL_mapCode | correlationId=${task.correlationId}" }
        val codeMap = codeMapper.run(task = convertTask(task), goal = goal)
        return json.encodeToString(codeMap)
    }

    @Tool
    @LLMDescription(
        "Propose technical specification for a plan step. MANDATORY before any coding/delegation. " +
            "Returns: DelegationSpec with target agent, files and instructions.",
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
