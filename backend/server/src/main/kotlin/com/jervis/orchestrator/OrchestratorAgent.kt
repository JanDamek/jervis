package com.jervis.orchestrator

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.dsl.builder.forwardTo
import ai.koog.agents.core.dsl.builder.strategy
import ai.koog.agents.core.dsl.extension.onIsInstance
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.reflect.tools
import ai.koog.prompt.dsl.Prompt
import com.jervis.common.client.IJoernClient
import com.jervis.configuration.RpcReconnectHandler
import com.jervis.integration.bugtracker.BugTrackerService
import com.jervis.integration.wiki.WikiService
import com.jervis.knowledgebase.KnowledgeService
import com.jervis.koog.KoogPromptExecutorFactory
import com.jervis.koog.SmartModelSelector
import com.jervis.koog.tools.CommunicationTools
import com.jervis.koog.tools.KnowledgeStorageTools
import com.jervis.koog.tools.analysis.JoernTools
import com.jervis.koog.tools.analysis.LogSearchTools
import com.jervis.koog.tools.coding.CodingTools
import com.jervis.koog.tools.external.BugTrackerReadTools
import com.jervis.koog.tools.external.EmailReadTools
import com.jervis.koog.tools.external.IssueTrackerTool
import com.jervis.koog.tools.external.WikiReadTools
import com.jervis.koog.tools.qualifier.QualifierRoutingTools
import com.jervis.koog.tools.scheduler.SchedulerTools
import com.jervis.koog.tools.user.UserInteractionTools
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
import com.jervis.orchestrator.model.ConversationContext
import com.jervis.orchestrator.model.GoalResult
import com.jervis.orchestrator.model.GoalSelection
import com.jervis.orchestrator.model.GoalSpec
import com.jervis.orchestrator.model.MultiGoalRequest
import com.jervis.orchestrator.model.OrchestratorCheckpoint
import com.jervis.orchestrator.model.RequestType
import com.jervis.orchestrator.tools.ChatHistoryTools
import com.jervis.orchestrator.tools.ExecutionMemoryTools
import com.jervis.orchestrator.tools.InternalAgentTools
import com.jervis.orchestrator.tools.ProjectStructureTools
import com.jervis.orchestrator.tools.ValidationTools
import com.jervis.service.background.TaskService
import com.jervis.service.connection.ConnectionService
import com.jervis.service.email.EmailService
import com.jervis.service.learning.LearningService
import com.jervis.service.link.IndexedLinkService
import com.jervis.service.link.LinkContentService
import com.jervis.service.preferences.PreferenceService
import com.jervis.service.project.ProjectService
import com.jervis.service.scheduling.TaskManagementService
import com.jervis.service.storage.DirectoryStructureService
import com.jervis.service.task.UserTaskService
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import mu.KotlinLogging
import org.springframework.context.annotation.Lazy
import org.springframework.stereotype.Service
import java.nio.file.Paths
import java.util.concurrent.ConcurrentHashMap
import com.jervis.entity.TaskDocument as EntityTaskDocument
import com.jervis.orchestrator.model.TaskDocument as ModelTaskDocument

/**
 * OrchestratorAgent - Main user-facing agent using pure Koog 0.6.0 patterns.
 *
 * Architecture:
 * 1. Get mandatory context (ContextAgent)
 * 2. Create execution plan (PlannerAgent)
 * 3. Execute plan steps (tool calls)
 * 4. Review completeness (ReviewerAgent)
 * 5. Iterate if needed or compose final answer
 *
 * Internal agents used as Tools:
 * - ContextAgent: Provide mandatory context
 * - PlannerAgent: Decompose query into ordered steps
 * - ResearchAgent: Gather evidence via tool-loop
 * - ReviewerAgent: Check completeness and create follow-ups
 *
 * All tools available:
 * - Knowledge: RAG, GraphDB
 * - Communication: Email, Slack, JIRA, Confluence
 * - System: Terminal, file operations, scheduling
 * - User interaction: UserTask creation
 *
 * Pure Koog 0.6.0 - no custom state management, only Koog nodes and edges.
 */
@Service
class OrchestratorAgent(
    private val knowledgeService: KnowledgeService,
    private val taskManagementService: TaskManagementService,
    private val userTaskService: UserTaskService,
    private val promptExecutorFactory: KoogPromptExecutorFactory,
    private val taskService: TaskService,
    private val linkContentService: LinkContentService,
    private val indexedLinkService: IndexedLinkService,
    private val smartModelSelector: SmartModelSelector,
    private val connectionService: ConnectionService,
    private val jiraService: BugTrackerService,
    private val confluenceService: WikiService,
    private val emailService: EmailService,
    private val codingTools: CodingTools,
    private val chatMessageRepository: com.jervis.repository.ChatMessageRepository,
    private val taskRepository: com.jervis.repository.TaskRepository,
    private val joernClient: IJoernClient,
    private val projectService: ProjectService,
    private val directoryStructureService: DirectoryStructureService,
    private val reconnectHandler: RpcReconnectHandler,
    @Lazy private val trackerAdapter: WorkTrackerAdapter,
    private val contextAgent: ContextAgent,
    private val plannerAgent: PlannerAgent,
    private val researchAgent: ResearchAgent,
    private val reviewerAgent: ReviewerAgent,
    private val solutionArchitectAgent: SolutionArchitectAgent,
    private val interpreterAgent: InterpreterAgent,
    private val workflowAnalyzer: WorkflowAnalyzer,
    private val programManager: ProgramManager,
    private val memoryRetriever: MemoryRetriever,
    private val evidenceCollector: EvidenceCollector,
    private val codeMapper: CodeMapper,
    private val goalExecutor: GoalExecutor,
    private val preferenceService: PreferenceService,
    private val learningService: LearningService,
) {
    companion object {
        private val logger = KotlinLogging.logger {}
        private val json =
            kotlinx.serialization.json.Json {
                ignoreUnknownKeys = true
                isLenient = true
                encodeDefaults = true
            }

        /**
         * Detect language from user input.
         * Simple heuristic: Czech words like "najdi", "které", "jsem"
         */
        private fun detectLanguage(text: String): String {
            val czechKeywords =
                listOf(
                    "najdi",
                    "které",
                    "všechny",
                    "jsem",
                    "který",
                    "jaký",
                    "v",
                    "na",
                    "ze",
                    "do",
                    "pro",
                    "kde",
                    "kdy",
                    "proč",
                    "řekni",
                    "udělej",
                    "vytvoř",
                    "napiš",
                )

            val lowerText = text.lowercase()
            val czechCount = czechKeywords.count { lowerText.contains(" $it ") || lowerText.startsWith("$it ") }

            return if (czechCount >= 2) "cs" else "en"
        }
    }

    private val activeAgents = ConcurrentHashMap<String, String>()

    fun isProviderInUse(providerName: String): Boolean = activeAgents.containsValue(providerName)

    /**
     * Create orchestrator agent instance - Multi-Goal Universal Orchestrator.
     * Sequential execution of multiple goals from complex user queries.
     *
     * @param task TaskDocument with user query
     * @param restoredCheckpoint Optional checkpoint from previous session for state restoration
     */
    suspend fun create(
        task: EntityTaskDocument,
        restoredCheckpoint: OrchestratorCheckpoint? = null,
    ): AIAgent<String, String> {
        val modelTask =
            ModelTaskDocument(
                id = task.id.value.toString(),
                clientId = task.clientId.value.toString(),
                projectId = task.projectId?.value?.toString(),
                type = task.type.name,
                content = task.content,
                state = task.state.name,
                correlationId = task.correlationId,
                sourceUrn = task.sourceUrn.value,
                createdAt = task.createdAt.toString(),
            )
        val model =
            smartModelSelector.selectModel(
                baseModelName = SmartModelSelector.BaseModelTypeEnum.AGENT,
                inputContent = task.content,
            )

        val promptExecutor = promptExecutorFactory.getExecutor("OLLAMA")

        var multiGoalRequest: MultiGoalRequest? = restoredCheckpoint?.multiGoalRequest
        var completedGoals = restoredCheckpoint?.completedGoals?.toMutableMap() ?: mutableMapOf()

        if (restoredCheckpoint != null && restoredCheckpoint.executionMemory.isNotEmpty()) {
            ExecutionMemoryTools.restoreMemory(task.correlationId, restoredCheckpoint.executionMemory)
            logger.info {
                "EXECUTION_MEMORY_RESTORED | correlationId=${task.correlationId} | " +
                    "entries=${restoredCheckpoint.executionMemory.size}"
            }
        }

        var conversationContext = restoredCheckpoint?.conversationContext

        val toolRegistry =
            ToolRegistry {
                tools(ChatHistoryTools(modelTask, chatMessageRepository, json))
                tools(ExecutionMemoryTools(task))
                tools(ValidationTools(task, knowledgeService))
                tools(
                    com.jervis.koog.tools.preferences.PreferenceTools(
                        task,
                        preferenceService,
                    ),
                )
                tools(
                    com.jervis.koog.tools.learning.LearningTools(
                        task,
                        learningService,
                    ),
                )
                tools(
                    ProjectStructureTools(
                        task,
                        directoryStructureService,
                        projectService,
                    ),
                )
                tools(
                    InternalAgentTools(
                        task,
                        contextAgent,
                        plannerAgent,
                        researchAgent,
                        reviewerAgent,
                        knowledgeService,
                        solutionArchitectAgent,
                        interpreterAgent,
                        workflowAnalyzer,
                        programManager,
                        memoryRetriever,
                        evidenceCollector,
                        codeMapper,
                    ),
                )
                tools(KnowledgeStorageTools(task, knowledgeService))
                tools(
                    QualifierRoutingTools(
                        task,
                        taskService,
                        linkContentService,
                        indexedLinkService,
                        connectionService,
                    ),
                )
                tools(SchedulerTools(task, taskManagementService))
                tools(UserInteractionTools(task, userTaskService, jiraService))
                tools(CommunicationTools(task))
                tools(codingTools)
                tools(IssueTrackerTool(task, jiraService, trackerAdapter))
                tools(BugTrackerReadTools(task, jiraService))
                tools(WikiReadTools(task, confluenceService))
                tools(EmailReadTools(task, emailService))
                tools(JoernTools(task, joernClient, projectService, directoryStructureService))
                tools(LogSearchTools(task, Paths.get("logs")))
            }

        val agentStrategy =
            strategy("Universal Multi-Goal Orchestrator") {
                val nodeDecompose by node<String, MultiGoalRequest> { userQuery ->
                    logger.info { "🎯 DECOMPOSITION | correlationId=${modelTask.correlationId}" }

                    val result = interpreterAgent.run(modelTask.copy(content = userQuery))
                    multiGoalRequest = result

                    logger.info {
                        "✓ DECOMPOSED | goals=${result.goals.size} | " +
                            "types=${result.goals.map { it.type }.distinct().joinToString()} | " +
                            "dependencies=${result.dependencyGraph.size} | correlationId=${modelTask.correlationId}"
                    }

                    result
                }

                val nodeSelectNext by node<MultiGoalRequest, GoalSelection> { request ->
                    val completedIds = completedGoals.keys
                    val executable = request.getExecutableGoals(completedIds)

                    when {
                        executable.isNotEmpty() -> {
                            val next = executable.minByOrNull { it.priority }!!

                            logger.info {
                                "▶️ NEXT_GOAL | id=${next.id} | type=${next.type} | " +
                                    "priority=${next.priority} | outcome='${next.outcome}' | " +
                                    "completed=${completedIds.size}/${request.goals.size} | " +
                                    "correlationId=${modelTask.correlationId}"
                            }

                            GoalSelection.Execute(next, request)
                        }

                        request.hasMoreGoals(completedIds) -> {
                            logger.warn {
                                "⏸️ WAITING_DEPENDENCIES | completed=${completedIds.size} | " +
                                    "total=${request.goals.size} | correlationId=${modelTask.correlationId}"
                            }
                            GoalSelection.WaitingForDependencies(request)
                        }

                        else -> {
                            logger.info {
                                "✅ ALL_GOALS_COMPLETE | total=${completedIds.size} | " +
                                    "correlationId=${modelTask.correlationId}"
                            }
                            GoalSelection.AllDone(request, completedGoals.values.toList())
                        }
                    }
                }

                val nodeExecuteGoal by node<GoalSpec, GoalResult> { goal ->
                    logger.info { "🚀 EXECUTE_GOAL_START | id=${goal.id} | correlationId=${modelTask.correlationId}" }

                    val dependencyResults =
                        multiGoalRequest!!
                            .dependencyGraph[goal.id]
                            ?.mapNotNull { depId -> completedGoals[depId] }
                            ?.associateBy { it.goalId }
                            ?: emptyMap()

                    val result =
                        goalExecutor.executeGoal(
                            goal = goal,
                            task = modelTask,
                            previousResults = dependencyResults,
                            toolRegistry = toolRegistry,
                        )

                    completedGoals[goal.id] = result

                    logger.info {
                        "✅ EXECUTE_GOAL_COMPLETE | id=${goal.id} | success=${result.success} | " +
                            "duration=${result.duration}ms | correlationId=${modelTask.correlationId}"
                    }

                    result
                }

                val nodeAggregate by node<List<GoalResult>, String> { results ->
                    logger.info { "🔗 AGGREGATION | goals=${results.size} | correlationId=${modelTask.correlationId}" }

                    if (results.size == 1 && results[0].goalType == RequestType.ADVICE) {
                        val result = results[0]
                        if (result.success && result.evidence != null) {
                            return@node result.evidence.summary.ifBlank {
                                result.evidence
                                    .combinedSummary()
                                    .ifBlank { "Nenalezl jsem žádné relevantní informace." }
                            }
                        }
                    }

                    buildString {
                        val hasMultipleGoals = results.size > 1
                        val hasCodeChanges = results.any { it.goalType == RequestType.CODE_CHANGE }

                        if (hasMultipleGoals || hasCodeChanges) {
                            appendLine("# 📋 Výsledky zpracování")
                            appendLine()
                            appendLine("Zpracováno **${results.size} úkolů** z původního požadavku:")
                            appendLine("_\"${multiGoalRequest!!.originalQuery}\"_")
                            appendLine()
                            appendLine("---")
                            appendLine()
                        }

                        results
                            .sortedBy { multiGoalRequest!!.goals.indexOfFirst { g -> g.id == it.goalId } }
                            .forEachIndexed { index, result ->
                                val icon =
                                    when {
                                        !result.success -> "❌"
                                        result.goalType == RequestType.CODE_CHANGE -> "🔧"
                                        result.goalType == RequestType.CODE_ANALYSIS -> "🔍"
                                        result.goalType == RequestType.ADVICE -> "💡"
                                        result.goalType == RequestType.MESSAGE_DRAFT -> "📧"
                                        result.goalType == RequestType.EPIC -> "🎯"
                                        result.goalType == RequestType.BACKLOG_PROGRAM -> "📊"
                                        else -> "✅"
                                    }

                                if (hasMultipleGoals || hasCodeChanges) {
                                    appendLine("## $icon ${index + 1}. ${result.outcome}")
                                    appendLine()
                                }

                                if (!result.success) {
                                    appendLine("**Chyba:** ${result.errors.joinToString(", ")}")
                                } else {
                                    if (result.evidence != null) {
                                        appendLine(result.evidence.summary.ifBlank { result.evidence.combinedSummary() })
                                    }

                                    if (result.artifacts.isNotEmpty() && hasCodeChanges) {
                                        appendLine()
                                        appendLine("**Změny:**")
                                        result.artifacts.forEach { artifact ->
                                            appendLine("- [${artifact.type}] ${artifact.description}")
                                        }
                                    }
                                }

                                if (hasMultipleGoals || hasCodeChanges) {
                                    appendLine()
                                    appendLine("---")
                                    appendLine()
                                }
                            }

                        if (hasMultipleGoals || hasCodeChanges) {
                            val successCount = results.count { it.success }
                            val failCount = results.size - successCount
                            val totalDuration = results.sumOf { it.duration }

                            appendLine("## 📊 Shrnutí")
                            appendLine("- ✅ Dokončeno: $successCount")
                            if (failCount > 0) {
                                appendLine("- ❌ Selhalo: $failCount")
                            }
                            appendLine("- ⏱️ Celková doba: ${totalDuration / 1000}s")
                        }
                    }
                }

                edge(nodeStart forwardTo nodeDecompose)
                edge(nodeDecompose forwardTo nodeSelectNext)
                val nodeExtractGoal by node<GoalSelection.Execute, GoalSpec> { it.goal }
                edge((nodeSelectNext forwardTo nodeExtractGoal).onIsInstance(GoalSelection.Execute::class))
                edge(nodeExtractGoal forwardTo nodeExecuteGoal)
                val nodeBackToSelect by node<GoalResult, MultiGoalRequest> { multiGoalRequest!! }
                edge(nodeExecuteGoal forwardTo nodeBackToSelect)
                edge(nodeBackToSelect forwardTo nodeSelectNext)
                val nodeExtractResults by node<GoalSelection.AllDone, List<GoalResult>> { it.results }
                edge((nodeSelectNext forwardTo nodeExtractResults).onIsInstance(GoalSelection.AllDone::class))
                edge(nodeExtractResults forwardTo nodeAggregate)
                edge(nodeAggregate forwardTo nodeFinish)
                val nodeWaitingError by node<GoalSelection.WaitingForDependencies, String> {
                    "⚠️ Některé úkoly nelze dokončit kvůli nevyřešeným závislostem."
                }
                edge((nodeSelectNext forwardTo nodeWaitingError).onIsInstance(GoalSelection.WaitingForDependencies::class))
                edge(nodeWaitingError forwardTo nodeFinish)
            }

        logger.info {
            "ORCHESTRATOR_CREATE | correlationId=${modelTask.correlationId} | " +
                "model=${model.id} | contextLength=${model.contextLength}"
        }

        val agentConfig =
            AIAgentConfig(
                prompt =
                    Prompt.build("universal-orchestrator") {
                        system(
                            """
You are JERVIS Universal Orchestrator - master coordinator for executing user requests.

## HOW TO HANDLE DIFFERENT REQUEST TYPES

### SEARCH/ANALYSIS Requests (like "find X", "search for Y", "what is Z")
SIMPLE WORKFLOW (do NOT overcomplicate):
1. Search the indexed knowledge base (contains documents, emails, code)
2. If not found, explore code structure using graphDB
3. Compose answer from results
4. DONE - return final answer to user

For simple searches you DON'T NEED complex planning!
Just search and answer!

### CODE_CHANGE Requests (like "fix bug", "add feature", "refactor code")
STANDARD WORKFLOW:
1. Get project location (NEVER invent paths!)
2. Explore knowledge base and code structure for affected parts
3. If user has budget, use coding agents (fast/thorough/premium)
4. Return changes made

### ADVICE Requests (like "how to...", "should I...", "recommend...")
WORKFLOW:
1. Search knowledge base for relevant information
2. Compose advice from found knowledge + your reasoning
3. Return recommendation

### COMPLEX MULTI-STEP Requests (like "find best practices online AND apply to code")
**THIS IS YOUR SPECIALTY - multi-step reasoning with validation!**

EXAMPLE: "Find Koog best practices online and update OrganizationAgent strategy"

MANDATORY WORKFLOW (follow EXACTLY):

**STEP 1: Decompose** into sub-goals with dependencies
```
Sub-goal A: Search internet for "Koog framework best practices"
Sub-goal B: Validate found practices against our Koog version (depends on A)
Sub-goal C: Find OrganizationAgent class location (independent)
Sub-goal D: Apply validated practices to OrganizationAgent (depends on B + C)
```

**STEP 2: Execute with validation loop**
For EACH web result:
1. Fetch external information from web
2. Validate version compatibility!
   - Is this for Koog 0.6 or older version?
   - Does it match our technology stack?
3. Cross-check against existing knowledge
   - Does existing KB say something opposite?
   - Is trust score high enough?
4. Remember validated findings
   - For use in next sub-goal

**STEP 3: Use execution memory**
- Store results of each sub-goal in memory
- Recall stored results when next sub-goal needs them
- You can review what you've already discovered

**STEP 4: Knowledge base enrichment**
- Store useful validated findings in knowledge base
- Categorize: "best-practices", "configuration", "code-patterns"
- Record source where it came from
- Include trust score from validation

**STEP 5: Autonomous Learning**
- When you discover successful patterns → storeLearning()
- When user corrects you → setPreference() with high confidence
- Load learnings at task start → retrieveLearnings()
- This makes you smarter over time!

**STEP 6: Adaptive Execution**
- If blocked or need clarification → askUser() and pause
- Task will resume when user responds
- Your checkpoint preserves all context
- Continue from where you left off

**STEP 7: Continuous cross-reference**
- BEFORE accepting ANY external info → validate compatibility!
- AFTER web search → check against KB
- Found conflict? → Flag for review, don't apply blindly

**CRITICAL VALIDATION CHECKS:**
 Version mismatch? (Tutorial for v0.5, we use v0.6) → REJECT or ADAPT
 Wrong framework? (Similar name but different library) → REJECT
 Deprecated API? (Mentions "removed in", "replaced by") → FLAG
 Contradicts existing knowledge? → Ask user for clarification

## YOUR CAPABILITIES

### Project Operations
You have access to:
- Getting real project paths (git, documents, etc.)
- Project information (name, description, git status)
- NEVER invent paths like `/home/user/projects/...` - they don't exist!
- ALWAYS get real path using available tools!

### Search and Analysis
You can:
- Full-text search in documents, emails, code (knowledge base)
- Structural queries into code (classes, functions, dependencies) via graphDB
- Advanced code analysis using Joern (FREE!)

For "find X" requests → START with KB search, NOT planning!

### Complex Workflows
For complex tasks you can:
- Interpret request (ONLY for EPIC/program management!)
- Get mandatory context (ALWAYS with userQuery parameter!)
- Create plan (ONLY after getting context, for multi-step tasks)
- Gather evidence in structured way

### User Interaction
You can:
- Ask user clarifying questions (creates UserTask, pauses execution)

### Code Modification
You have coding agents available:
- FAST (quick local changes 1-3 files)
- THOROUGH (complex refactoring across multiple files)
- PREMIUM (very fast and high quality, but EXPENSIVE - use sparingly!)

### External Sources
You can:
- Search the internet
- Read web pages
- Communicate with JIRA, Confluence
- Work with emails

### Memory and Knowledge
You can:
- Store intermediate results in execution memory (for multi-step reasoning)
- Recall previously stored results
- Validate external information against our stack
- Cross-check new findings with existing KB
- Store validated knowledge in long-term storage

## COST AWARENESS
- Local models = FREE (use freely!)
- Cloud coding agents = PAID (check budget!)
- RAG, GraphDB, Joern = FREE (ALWAYS use BEFORE paid tools!)

## ANTI-PATTERNS - NEVER DO THIS!
 Inventing file paths like "/home/user/projects/..."
 Using interpretation for simple "find X" requests
 Creating elaborate plans for "search in knowledge base" tasks
 Calling tools repeatedly in loop without progress
 Ignoring tool errors and continuing

## SUCCESS PATTERN - FOLLOW THIS!
 Simple search → search in KB → answer
 Need path → get project path → use returned path
 Complex task → get context → create plan → execute
 Tool error → read error message → fix parameters → retry ONCE

Always answer in user's language (usually Czech).
Be thorough but concise.
                            """.trimIndent(),
                        )
                    },
                model = model,
                maxAgentIterations = 200,
            )

        return AIAgent(
            promptExecutor = promptExecutor,
            toolRegistry = toolRegistry,
            strategy = agentStrategy,
            agentConfig = agentConfig,
        )
    }

    /**
     * Run orchestrator agent with message history persistence.
     *
     * Workflow:
     * 1. Save user message to ChatMessageDocument collection
     * 2. Restore agent checkpoint from previous TaskDocument if exists
     * 3. Create and run agent instance
     * 4. Save assistant response to ChatMessageDocument collection
     * 5. Save agent checkpoint to current TaskDocument
     *
     * @param onProgress Callback for sending progress updates to client
     */
    suspend fun run(
        task: EntityTaskDocument,
        userInput: String,
        onProgress: suspend (message: String, metadata: Map<String, String>) -> Unit = { _, _ -> },
    ): String {
        val startTime = System.currentTimeMillis()

        logger.info {
            "🎯 ORCHESTRATOR_START | taskId=${task.id} | correlationId=${task.correlationId} | " +
                "inputLength=${userInput.length}"
        }

        return try {
            onProgress("Zahajuji analýzu požadavku...", mapOf("step" to "init", "agent" to "orchestrator"))

            val conversationHistory =
                chatMessageRepository
                    .findByTaskIdOrderBySequenceAsc(task.id)
                    .toList()

            val hasHistory = conversationHistory.isNotEmpty()
            if (hasHistory) {
                logger.info {
                    "CONVERSATION_HISTORY_LOADED | taskId=${task.id} | messages=${conversationHistory.size} | " +
                        "lastSequence=${conversationHistory.lastOrNull()?.sequence}"
                }
                onProgress(
                    "Načítám kontext konverzace (${conversationHistory.size} zpráv)...",
                    mapOf("step" to "context_loading", "messageCount" to "${conversationHistory.size}"),
                )
            }

            var restoredCheckpoint: OrchestratorCheckpoint? = null
            if (task.agentCheckpointJson != null) {
                try {
                    val checkpoint = json.decodeFromString<OrchestratorCheckpoint>(task.agentCheckpointJson)
                    restoredCheckpoint = checkpoint
                    logger.info {
                        "CHECKPOINT_RESTORED | taskId=${task.id} | " +
                            "version=${checkpoint.version} | " +
                            "goals=${checkpoint.multiGoalRequest?.goals?.size} | " +
                            "completed=${checkpoint.completedGoals.size} | " +
                            "language=${checkpoint.conversationContext.language} | " +
                            "waitingForInput=${checkpoint.waitingForUserInput}"
                    }
                } catch (e: Exception) {
                    try {
                        val legacyData = json.decodeFromString<Map<String, String>>(task.agentCheckpointJson)
                        logger.info {
                            "LEGACY_CHECKPOINT_DETECTED | taskId=${task.id} | " +
                                "messageCount=${legacyData["messageCount"]} - treating as fresh start"
                        }
                        restoredCheckpoint = null
                    } catch (e2: Exception) {
                        logger.warn(e) { "CHECKPOINT_RESTORE_FAILED | taskId=${task.id} - will continue without checkpoint" }
                    }
                }
            }

            val agent: AIAgent<String, String> = create(task, restoredCheckpoint)
            onProgress(
                "Agent inicializován, připravuji zpracování...",
                mapOf("step" to "agent_ready", "agent" to "orchestrator"),
            )

            val model =
                smartModelSelector.selectModel(
                    baseModelName = SmartModelSelector.BaseModelTypeEnum.AGENT,
                    inputContent = task.content,
                )
            activeAgents[task.correlationId] = "OLLAMA"

            onProgress(
                "Analyzuji požadavek a rozděluji na dílčí cíle...",
                mapOf("step" to "decomposition", "model" to model.id, "phase" to "planning"),
            )

            val output: String = agent.run(userInput)
            val duration = System.currentTimeMillis() - startTime

            onProgress(
                "Zpracování dokončeno, finalizuji odpověď...",
                mapOf("step" to "finalizing", "duration" to "${duration}ms"),
            )

            val assistantMessageSequence = chatMessageRepository.countByTaskId(task.id) + 1
            val assistantMessage =
                com.jervis.entity.ChatMessageDocument(
                    taskId = task.id,
                    correlationId = task.correlationId,
                    role = com.jervis.entity.MessageRole.ASSISTANT,
                    content = output,
                    sequence = assistantMessageSequence,
                    timestamp = java.time.Instant.now(),
                )
            chatMessageRepository.save(assistantMessage)
            logger.info { "🤖 ASSISTANT_MESSAGE_SAVED | taskId=${task.id} | sequence=$assistantMessageSequence" }

            try {
                val detectedLanguage =
                    restoredCheckpoint?.conversationContext?.language
                        ?: detectLanguage(userInput)

                val checkpoint =
                    OrchestratorCheckpoint(
                        multiGoalRequest = null,
                        completedGoals = emptyMap(),
                        currentGoalId = null,
                        currentGoalPhase = null,
                        executionMemory = ExecutionMemoryTools.getMemory(task.correlationId),
                        waitingForUserInput = false,
                        userQuestion = null,
                        resumePoint = null,
                        conversationContext =
                            ConversationContext(
                                language = detectedLanguage,
                                tone = "technical",
                                detailLevel = "detailed",
                                preferences = emptyMap(), // TODO: load from PreferenceService
                            ),
                        version = "2.0",
                    )

                val checkpointJson =
                    kotlinx.serialization.json.Json
                        .encodeToString(checkpoint)

                val updatedTask = task.copy(agentCheckpointJson = checkpointJson)
                taskRepository.save(updatedTask)

                logger.info {
                    "CHECKPOINT_SAVED | taskId=${task.id} | version=2.0 | " +
                        "checkpointSize=${checkpointJson.length} | language=$detectedLanguage"
                }
            } catch (e: Exception) {
                logger.warn(e) { "CHECKPOINT_SAVE_FAILED | taskId=${task.id} - agent will start fresh on next run" }
            }

            logger.info {
                "✅ ORCHESTRATOR_SUCCESS | correlationId=${task.correlationId} | " +
                    "duration=${duration}ms | outputLength=${output.length}"
            }

            output
        } catch (e: Exception) {
            val duration = System.currentTimeMillis() - startTime
            logger.error(e) {
                "❌ ORCHESTRATOR_FAILED | correlationId=${task.correlationId} | " +
                    "duration=${duration}ms | error=${e.message}"
            }
            throw e
        } finally {
            activeAgents.remove(task.correlationId)
        }
    }

    @Serializable
    @SerialName("OrchestratorDecision")
    @LLMDescription(
        "Routing decision for the orchestrator: finish with the final user answer",
    )
    private data class OrchestratorDecision(
        @property:LLMDescription("Decision type: FINAL means return final answer to user")
        val type: DecisionType,
        @property:LLMDescription("Final answer to show to the user when type=FINAL. Must be in the user's language.")
        val finalAnswer: String? = null,
    )

    @Serializable
    @SerialName("DecisionType")
    private enum class DecisionType { FINAL, }

    private sealed interface NextAction {
        data class Final(
            val answer: String,
        ) : NextAction
    }

    private fun buildDecisionPrompt(lastAssistantMessage: String): String =
        """
        Decide the NEXT orchestrator action based on the conversation so far.

        - If you are ready to respond to the user, choose type=FINAL and fill finalAnswer.

        Context: last assistant message (for reference only):
        ---
        $lastAssistantMessage
        ---
        """.trimIndent()
}
