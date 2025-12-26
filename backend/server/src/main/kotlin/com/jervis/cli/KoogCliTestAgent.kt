package com.jervis.cli

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.agent.entity.createStorageKey
import ai.koog.agents.core.dsl.builder.forwardTo
import ai.koog.agents.core.dsl.builder.strategy
import ai.koog.agents.core.dsl.extension.nodeExecuteTool
import ai.koog.agents.core.dsl.extension.nodeLLMRequest
import ai.koog.agents.core.dsl.extension.nodeLLMRequestStructured
import ai.koog.agents.core.dsl.extension.nodeLLMSendToolResult
import ai.koog.agents.core.dsl.extension.onAssistantMessage
import ai.koog.agents.core.dsl.extension.onToolCall
import ai.koog.agents.core.feature.handler.agent.AgentCompletedContext
import ai.koog.agents.core.feature.handler.agent.AgentStartingContext
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import ai.koog.agents.core.tools.reflect.tools
import ai.koog.agents.ext.tool.file.EditFileTool
import ai.koog.agents.ext.tool.file.ListDirectoryTool
import ai.koog.agents.ext.tool.file.ReadFileTool
import ai.koog.agents.ext.tool.file.WriteFileTool
import ai.koog.agents.ext.tool.shell.ExecuteShellCommandTool
import ai.koog.agents.ext.tool.shell.JvmShellCommandExecutor
import ai.koog.agents.ext.tool.shell.PrintShellCommandConfirmationHandler
import ai.koog.agents.features.eventHandler.feature.EventHandler
import ai.koog.agents.features.tracing.feature.Tracing
import ai.koog.agents.features.tracing.writer.TraceFeatureMessageLogWriter
import ai.koog.prompt.dsl.Prompt
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.structure.StructuredResponse
import ai.koog.rag.base.files.JVMFileSystemProvider
import com.jervis.common.client.IAiderClient
import com.jervis.common.client.ICodingEngineClient
import com.jervis.dto.TaskStateEnum
import com.jervis.dto.TaskTypeEnum
import com.jervis.entity.TaskDocument
import com.jervis.koog.SmartModelSelector
import com.jervis.koog.tools.AiderCodingTool
import com.jervis.koog.tools.CommunicationTools
import com.jervis.koog.tools.KnowledgeStorageTools
import com.jervis.koog.tools.OpenHandsCodingTool
import com.jervis.koog.tools.project.ProjectDiscoveryTools
import com.jervis.koog.tools.qualifier.QualifierRoutingTools
import com.jervis.koog.tools.scheduler.SchedulerTools
import com.jervis.koog.tools.user.UserInteractionTools
import com.jervis.rag.KnowledgeService
import com.jervis.rag.internal.graphdb.GraphDBService
import com.jervis.repository.ProjectRepository
import com.jervis.service.background.TaskService
import com.jervis.service.connection.ConnectionService
import com.jervis.service.link.IndexedLinkService
import com.jervis.service.link.LinkContentService
import com.jervis.service.scheduling.TaskManagementService
import com.jervis.service.task.UserTaskService
import com.jervis.types.ClientId
import com.jervis.types.SourceUrn
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.Serializable
import org.bson.types.ObjectId

/**
 * KoogCliTestAgent: Lightweight orchestrator using an Agent-as-Tool pattern.
 *
 * Notes:
 * - The system uses a single `Task` persistence model (no separate PendingTask collection).
 * - Task routing is driven by `TaskTypeEnum` and `TaskStateEnum`.
 *
 * Architecture:
 * - Thin orchestrator coordinating specialist agents
 * - Each agent exposed as Koog Tool
 * - Agents: Intake, Scope, Triage, Discovery, Decomposition, Dispatcher, Finalizer
 * - Execution agents: Communication, JIRA, Coding, Analysis
 */
class KoogCliTestAgent(
    private val knowledgeService: KnowledgeService,
    private val graphDBService: GraphDBService,
    private val taskManagementService: TaskManagementService,
    private val userTaskService: UserTaskService,
    private val taskService: TaskService,
    private val linkContentService: LinkContentService,
    private val indexedLinkService: IndexedLinkService,
    private val connectionService: ConnectionService,
    private val projectRepository: ProjectRepository,
    private val aiderClient: IAiderClient,
    private val codingEngineClient: ICodingEngineClient,
    private val smartModelSelector: SmartModelSelector,
) {
    private val logger = KotlinLogging.logger {}

    fun create(
        promptExecutor: PromptExecutor,
        clientId: String,
    ): AIAgent<String, OrchestratorResponse> {
        val testCliTask =
            TaskDocument(
                type = TaskTypeEnum.USER_INPUT_PROCESSING,
                content = "CLI Test Task",
                clientId = ClientId(ObjectId(clientId)),
                projectId = null,
                sourceUrn = SourceUrn("cli://test"),
                state = TaskStateEnum.NEW,
            )

        val intakeAgentTool = IntakeAgentTool(promptExecutor, smartModelSelector)
        val scopeAgentTool =
            ClientProjectScopeAgentTool(
                promptExecutor,
                ProjectDiscoveryTools(testCliTask, projectRepository),
                smartModelSelector,
            )

        // Tool registry with agent tools + service tools
        val toolRegistry =
            ToolRegistry {
                // Agent tools (ToolSet - reflection)
                tools(intakeAgentTool)
                tools(scopeAgentTool)

                // Knowledge storage and retrieval (unified RAG + Graph)
                tools(KnowledgeStorageTools(testCliTask, knowledgeService, graphDBService))

                // Routing & Delegation
                tools(
                    QualifierRoutingTools(
                        testCliTask,
                        taskService,
                        linkContentService,
                        indexedLinkService,
                        connectionService,
                    ),
                )

                // Scheduling
                tools(SchedulerTools(testCliTask, taskManagementService))

                // User Interaction
                tools(UserInteractionTools(testCliTask, userTaskService))

                // Project Discovery
                tools(ProjectDiscoveryTools(testCliTask, projectRepository))

                // Communication
                tools(CommunicationTools(testCliTask))

                // File/shell tools (Tool<*, *>)
                tools(
                    listOf(
                        ListDirectoryTool(JVMFileSystemProvider.ReadOnly),
                        ReadFileTool(JVMFileSystemProvider.ReadOnly),
                        EditFileTool(JVMFileSystemProvider.ReadWrite),
                        WriteFileTool(JVMFileSystemProvider.ReadWrite),
                        ExecuteShellCommandTool(JvmShellCommandExecutor(), PrintShellCommandConfirmationHandler()),
                    ),
                )

                // Coding tools (ToolSet)
                tools(AiderCodingTool(testCliTask, aiderClient))
                tools(OpenHandsCodingTool(testCliTask, codingEngineClient))
            }

        // Tool-loop orchestrator strategy
        val agentStrategy =
            strategy<String, OrchestratorResponse>("Orchestrator: Coordinate agents") {
                val callLLM by nodeLLMRequest()
                val execTool by nodeExecuteTool()
                val sendToolResult by nodeLLMSendToolResult()

                // Start: user input → LLM
                edge(nodeStart forwardTo callLLM)

                // LLM tool call → execute tool
                edge(
                    (callLLM forwardTo execTool)
                        onToolCall { true },
                )

                // Tool result → send back to LLM
                edge(execTool forwardTo sendToolResult)

                // After sending a tool result, LLM may call another tool
                edge(
                    (sendToolResult forwardTo execTool)
                        onToolCall { true },
                )

                // LLM assistant message → finish (parse final response)
                edge(
                    (callLLM forwardTo nodeFinish)
                        onAssistantMessage { true }
                        transformed { assistantMsg ->
                            logger.info { "ORCHESTRATOR | Final assistant message: $assistantMsg" }
                            OrchestratorResponse(
                                summary = assistantMsg,
                                intent = IntentType.UNKNOWN,
                                projects = emptyList(),
                            )
                        },
                )

                // After the tool result, if LLM responds with an assistant message → finish
                edge(
                    (sendToolResult forwardTo nodeFinish)
                        onAssistantMessage { true }
                        transformed { assistantMsg ->
                            logger.info { "ORCHESTRATOR | Final assistant message after tools: $assistantMsg" }
                            OrchestratorResponse(
                                summary = assistantMsg,
                                intent = IntentType.UNKNOWN,
                                projects = emptyList(),
                            )
                        },
                )
            }

        val agentConfig =
            AIAgentConfig(
                prompt =
                    Prompt.build("orchestrator-agent") {
                        system(
                            """
You are an orchestrator coordinating specialist agents and tools to solve complex tasks end-to-end.

## Orchestration Strategy
1. Parse user request to understand intent, entities, unknowns, and constraints
2. Resolve project context and gather evidence from all available sources
3. Use tools to gather facts - DO NOT ASSUME ANYTHING
4. Coordinate specialist agents as needed
5. Draft communications but DO NOT SEND without user confirmation

## Critical Rules
- ALWAYS cite sources (ticket IDs, Confluence pages, RAG refs, graph nodes)
- NO assumptions - if data is missing, use discovery tools
- For conflicts between sources, list all sources and let user decide
- When drafting communications (email/slack/teams), ALWAYS get user confirmation before sending
- Use evidence-driven approach: gather facts first, then reason about them

Your role: coordinate tool execution to solve the task evidence-based, not perform tasks directly.
                            """.trimIndent(),
                        )
                    },
                model =
                    LLModel(
                        id = "qwen3-coder-tool-32k:30b",
                        provider = LLMProvider.Ollama,
                        contextLength = 32768,
                        capabilities =
                            listOf(
                                LLMCapability.Tools,
                                LLMCapability.Schema.JSON.Basic,
                                LLMCapability.Temperature,
                            ),
                    ),
                maxAgentIterations = 50,
            )

        return AIAgent(
            promptExecutor = promptExecutor,
            toolRegistry = toolRegistry,
            strategy = agentStrategy,
            agentConfig = agentConfig,
            installFeatures = {
                val logger = KotlinLogging.logger {}
                install(feature = EventHandler) {
                    onAgentStarting { ctx: AgentStartingContext ->
                        logger.info { "🚀 KoogCliTestAgent STARTING | agentId=${ctx.agent.id}" }
                    }
                    onAgentCompleted { ctx: AgentCompletedContext ->
                        logger.info { "✅ KoogCliTestAgent COMPLETED | result=${ctx.result}" }
                    }
                }
                install(feature = Tracing) {
                    addMessageProcessor(TraceFeatureMessageLogWriter(logger))
                }
            },
        )
    }
}

/**
 * ClientProjectScopeAgent: Resolves client and project context early in pipeline.
 *
 * Input: ScopeArgs (compact: clientId, ticketId, keywords, entities)
 * Output: ProjectScopeSummary (compact, 1-3 KB)
 *
 * Responsibilities:
 * - Resolve clientId
 * - Find top 3-5 relevant projects based on JIRA keys, systems, repos
 * - Extract key facts (concise)
 * - Store references to detailed context (URNs)
 * - Set certainty level (CERTAIN/CANDIDATES/UNKNOWN)
 *
 * Tools: resolveProjectContext, queryGraph, lookupOwnership (minimal read-only)
 * Model: Can use bigger context if needed (CPU/RAM), but output always compact
 */
class ClientProjectScopeAgent(
    private val smartModelSelector: SmartModelSelector,
) {
    private val logger = KotlinLogging.logger {}

    fun create(
        promptExecutor: PromptExecutor,
        projectDiscoveryTools: ProjectDiscoveryTools,
    ): AIAgent<ScopeArgs, ProjectScopeSummary> {
        val toolRegistry =
            ToolRegistry {
                tools(projectDiscoveryTools)
            }

        val agentStrategy =
            strategy<ScopeArgs, ProjectScopeSummary>("ClientProjectScope: Resolve context") {
                val inputKey = createStorageKey<ScopeArgs>("input")

                val nodePrompt by node<ScopeArgs, String>("Build Prompt") { input ->
                    """
Resolve top 3-5 relevant projects using available tools.

Hints:
- JIRA keys: ${input.jiraKeys.joinToString()}
- Keywords: ${input.keywords ?: "none"}

Output ProjectScopeSummary with:
- projects: 3-5 project names
- certainty: CERTAIN if single confirmed project, CANDIDATES if multiple, UNKNOWN if none
                    """.trimIndent()
                }

                val nodeLLMLoop by nodeLLMRequest("LLM Tool Loop")

                val nodeExecuteTool by nodeExecuteTool("Execute Tool")

                val nodeSendResult by nodeLLMSendToolResult("Send Tool Result")

                val nodeFinalizePrompt by node<Unit, String>("Finalize Prompt") {
                    "Provide final ProjectScopeSummary based on tool results."
                }

                val nodeFinalizeStructured by nodeLLMRequestStructured<ProjectScopeSummary>(
                    name = "Finalize Structured",
                )

                val nodeMapResult by node<Result<StructuredResponse<ProjectScopeSummary>>, ProjectScopeSummary>(
                    "Extract Result",
                ) { result ->
                    val summary = result.getOrThrow().structure
                    logger.info {
                        "SCOPE | projects=${summary.projects.size} | certainty=${summary.certainty}"
                    }
                    summary
                }

                val nodeStoreInput by node<ScopeArgs, ScopeArgs>("Store Input") { input ->
                    storage.set(inputKey, input)
                    input
                }

                // Graph wiring
                edge(nodeStart forwardTo nodeStoreInput)
                edge(nodeStoreInput forwardTo nodePrompt)
                edge(nodePrompt forwardTo nodeLLMLoop)

                // Tool loop - simple pattern without loop back
                edge(
                    nodeLLMLoop forwardTo nodeExecuteTool
                        onToolCall { true },
                )
                edge(nodeExecuteTool forwardTo nodeSendResult)

                // After tool result, finalize (simplified - no loop back)
                edge(
                    nodeSendResult forwardTo nodeFinalizePrompt
                        transformed { },
                )

                // If no tool called initially, go to finalize
                edge(
                    nodeLLMLoop forwardTo nodeFinalizePrompt
                        onAssistantMessage { true }
                        transformed { },
                )

                edge(nodeFinalizePrompt forwardTo nodeFinalizeStructured)
                edge(nodeFinalizeStructured forwardTo nodeMapResult)
                edge(nodeMapResult forwardTo nodeFinish)
            }

        val agentConfig =
            AIAgentConfig(
                prompt =
                    Prompt.build("scope-agent") {
                        system(
                            """
You resolve project context efficiently. Use tools to find top 3-5 relevant projects.
Keep output compact.
                            """.trimIndent(),
                        )
                    },
                model =
                    smartModelSelector.selectModel(
                        SmartModelSelector.BaseModelTypeEnum.AGENT,
                    ),
                maxAgentIterations = 10,
            )

        return AIAgent(
            promptExecutor = promptExecutor,
            toolRegistry = toolRegistry,
            strategy = agentStrategy,
            agentConfig = agentConfig,
            installFeatures = {
                val logger = KotlinLogging.logger {}
                install(feature = EventHandler) {
                    onAgentStarting { ctx: AgentStartingContext ->
                        logger.info { "🚀 ClientProjectScopeAgent STARTING | agentId=${ctx.agent.id}" }
                    }
                    onAgentCompleted { ctx: AgentCompletedContext ->
                        logger.info {
                            "✅ ClientProjectScopeAgent COMPLETED | result=${
                                ctx.result
                            }"
                        }
                    }
                }
                install(feature = Tracing) {
                    addMessageProcessor(TraceFeatureMessageLogWriter(logger))
                }
            },
        )
    }
}

@LLMDescription("Resolve client and project context")
class ClientProjectScopeAgentTool(
    private val promptExecutor: PromptExecutor,
    private val projectDiscoveryTools: ProjectDiscoveryTools,
    private val smartModelSelector: SmartModelSelector,
) : ToolSet {
    private val logger = KotlinLogging.logger {}

    @Tool
    @LLMDescription("Resolve project context from JIRA keys and keywords")
    suspend fun runScopeAgent(
        @LLMDescription("Scope arguments: jiraKeys and keywords")
        args: ScopeArgs,
    ): ProjectScopeSummary {
        logger.info { "runScopeAgent | jiraKeys=${args.jiraKeys} | keywords=${args.keywords}" }
        val startTime = System.currentTimeMillis()

        val agent =
            ClientProjectScopeAgent(smartModelSelector).create(
                promptExecutor = promptExecutor,
                projectDiscoveryTools = projectDiscoveryTools,
            )
        val result = agent.run(args)

        val duration = System.currentTimeMillis() - startTime
        logger.info { "runScopeAgent | duration=${duration}ms | projects=${result.projects.size}" }
        return result
    }
}

/**
 * CodingExecutionAgent: Code operations + Aider/OpenHands integration.
 *
 * Tool allowlist:
 * - readFile
 * - writeFile
 * - runShell
 * - callAider (future)
 * - callOpenHands (future)
 */
class CodingExecutionAgent(
    private val smartModelSelector: SmartModelSelector,
) {
    private val logger = KotlinLogging.logger {}

    fun create(promptExecutor: PromptExecutor): AIAgent<DecompositionAgent.Workstream, String> {
        val toolRegistry =
            ToolRegistry {
                // TODO: Add coding tools
            }

        val agentStrategy =
            strategy<DecompositionAgent.Workstream, String>("Coding Execution") {
                val nodePrompt by node<DecompositionAgent.Workstream, String>("Build Prompt") { workstream ->
                    "Execute coding workstream: ${workstream.description}"
                }

                val nodePlaceholder by node<String, String>("Placeholder") { input ->
                    logger.info { "CODING | $input" }
                    "Code changes executed (placeholder)"
                }

                edge(nodeStart forwardTo nodePrompt)
                edge(nodePrompt forwardTo nodePlaceholder)
                edge(nodePlaceholder forwardTo nodeFinish)
            }

        val agentConfig =
            AIAgentConfig(
                prompt =
                    Prompt.build("coding-execution-agent") {
                        system("You handle code modifications and tool integrations.")
                    },
                model = smartModelSelector.selectModel(SmartModelSelector.BaseModelTypeEnum.AGENT),
                maxAgentIterations = 15,
            )

        return AIAgent(
            promptExecutor = promptExecutor,
            toolRegistry = toolRegistry,
            strategy = agentStrategy,
            agentConfig = agentConfig,
            installFeatures = {
                val logger = KotlinLogging.logger {}
                install(feature = EventHandler) {
                    onAgentStarting { ctx: AgentStartingContext ->
                        logger.info { "🚀 CodingExecutionAgent STARTING | agentId=${ctx.agent.id}" }
                    }
                    onAgentCompleted { ctx: AgentCompletedContext ->
                        logger.info { "✅ CodingExecutionAgent COMPLETED | result=${ctx.result}" }
                    }
                }
                install(feature = Tracing) {
                    addMessageProcessor(TraceFeatureMessageLogWriter(logger))
                }
            },
        )
    }
}

/**
 * CommunicationExecutionAgent: Handles email/Slack drafts and sends.
 *
 * Tool allowlist:
 * - draftEmail
 * - sendEmail
 * - draftSlackMessage
 * - sendSlackMessage
 */
class CommunicationExecutionAgent(
    private val smartModelSelector: SmartModelSelector,
) {
    private val logger = KotlinLogging.logger {}

    fun create(promptExecutor: PromptExecutor): AIAgent<DecompositionAgent.Workstream, String> {
        val toolRegistry =
            ToolRegistry {
                // TODO: Add communication tools
            }

        val agentStrategy =
            strategy<DecompositionAgent.Workstream, String>("Communication Execution") {
                val nodePrompt by node<DecompositionAgent.Workstream, String>("Build Prompt") { workstream ->
                    "Execute communication workstream: ${workstream.description}"
                }

                val nodePlaceholder by node<String, String>("Placeholder") { input ->
                    logger.info { "COMMUNICATION | $input" }
                    "Communication executed (placeholder)"
                }

                edge(nodeStart forwardTo nodePrompt)
                edge(nodePrompt forwardTo nodePlaceholder)
                edge(nodePlaceholder forwardTo nodeFinish)
            }

        val agentConfig =
            AIAgentConfig(
                prompt =
                    Prompt.build("communication-execution-agent") {
                        system("You handle email and Slack communications.")
                    },
                model = smartModelSelector.selectModel(SmartModelSelector.BaseModelTypeEnum.AGENT),
                maxAgentIterations = 10,
            )

        return AIAgent(
            promptExecutor = promptExecutor,
            toolRegistry = toolRegistry,
            strategy = agentStrategy,
            agentConfig = agentConfig,
            installFeatures = {
                val logger = KotlinLogging.logger {}
                install(feature = EventHandler) {
                    onAgentStarting { ctx: AgentStartingContext ->
                        logger.info { "🚀 CommunicationExecutionAgent STARTING | agentId=${ctx.agent.id}" }
                    }
                    onAgentCompleted { ctx: AgentCompletedContext ->
                        logger.info {
                            "✅ CommunicationExecutionAgent COMPLETED | result=${
                                ctx.result
                            }"
                        }
                    }
                }
                install(feature = Tracing) {
                    addMessageProcessor(TraceFeatureMessageLogWriter(logger))
                }
            },
        )
    }
}

/**
 * DecompositionAgent: Creates ExecutionPlan - workstreams and sequencing.
 *
 * Responsibilities:
 * - Break complex task into parallel workstreams
 * - Define dependencies between workstreams
 * - Assign each workstream to specialist agent
 * - Plan WITHOUT executing
 *
 * Characteristics:
 * - NO TOOLS (pure planning, no execution)
 * - CPU model with big context
 * - Deterministic decomposition rules
 */
class DecompositionAgent(
    private val smartModelSelector: SmartModelSelector,
) {
    private val logger = KotlinLogging.logger {}

    data class DecompositionInput(
        val intake: IntakeDocument,
        val discovery: DiscoveryAgent.DiscoveryDocument,
    )

    fun create(promptExecutor: PromptExecutor): AIAgent<DecompositionInput, ExecutionPlan> {
        val toolRegistry =
            ToolRegistry {
                // NO TOOLS - pure planning
            }

        val agentStrategy =
            strategy<DecompositionInput, ExecutionPlan>("Decompose: Create plan") {
                val nodePrompt by node<DecompositionInput, String>("Build Prompt") { input ->
                    """
Create execution plan based on intent and evidence.

Intent: ${input.intake.intent}
JIRA keys: ${input.intake.jiraKeys.joinToString(", ")}
Urgency: ${input.intake.urgency}
Evidence: ${input.discovery.evidenceGathered.size} items

Break into workstreams:
- COMMUNICATION: Emails, Slack, notifications
- JIRA: Issue updates, comments
- CODING: Code changes, reviews
- ANALYSIS: Read-only analysis, reports

Define:
1. Workstream type
2. Dependencies (which must complete first)
3. Success criteria
                    """.trimIndent()
                }

                val nodeDecompose by nodeLLMRequestStructured<ExecutionPlan>(
                    name = "Extract ExecutionPlan",
                    examples =
                        listOf(
                            ExecutionPlan(
                                workstreams =
                                    listOf(
                                        Workstream(
                                            id = "ws-1",
                                            type = WorkstreamType.CODING,
                                            description = "Fix AUTH-123 login bug",
                                            dependencies = emptyList(),
                                            agentAssignment = "CodingExecutionAgent",
                                        ),
                                        Workstream(
                                            id = "ws-2",
                                            type = WorkstreamType.COMMUNICATION,
                                            description = "Email team about fix",
                                            dependencies = listOf("ws-1"),
                                            agentAssignment = "CommunicationExecutionAgent",
                                        ),
                                    ),
                            ),
                        ),
                )

                val nodeMapResult by node<Result<StructuredResponse<ExecutionPlan>>, ExecutionPlan>(
                    "Extract Result",
                ) { result ->
                    val plan = result.getOrThrow().structure
                    logger.info { "DECOMPOSITION | workstreams=${plan.workstreams.size}" }
                    plan
                }

                edge(nodeStart forwardTo nodePrompt)
                edge(nodePrompt forwardTo nodeDecompose)
                edge(nodeDecompose forwardTo nodeMapResult)
                edge(nodeMapResult forwardTo nodeFinish)
            }

        val agentConfig =
            AIAgentConfig(
                prompt =
                    Prompt.build("decomposition-agent") {
                        system(
                            """
You are a planning agent. Create execution plan WITHOUT executing.
Break complex tasks into parallel workstreams with clear dependencies.
Assign each workstream to appropriate specialist agent.
                            """.trimIndent(),
                        )
                    },
                model = smartModelSelector.selectModel(SmartModelSelector.BaseModelTypeEnum.AGENT),
                maxAgentIterations = 5,
            )

        return AIAgent(
            promptExecutor = promptExecutor,
            toolRegistry = toolRegistry,
            strategy = agentStrategy,
            agentConfig = agentConfig,
            installFeatures = {
                val logger = KotlinLogging.logger {}
                install(feature = EventHandler) {
                    onAgentStarting { ctx: AgentStartingContext ->
                        logger.info { "🚀 DecompositionAgent STARTING | agentId=${ctx.agent.id}" }
                    }
                    onAgentCompleted { ctx: AgentCompletedContext ->
                        logger.info { "✅ DecompositionAgent COMPLETED | result=${ctx.result}" }
                    }
                }
                install(feature = Tracing) {
                    addMessageProcessor(TraceFeatureMessageLogWriter(logger))
                }
            },
        )
    }

    @Serializable
    data class ExecutionPlan(
        val workstreams: List<Workstream>,
    )

    @Serializable
    data class Workstream(
        val id: String,
        val type: WorkstreamType,
        val description: String,
        val dependencies: List<String>, // Workstream IDs that must complete first
        val agentAssignment: String, // Which specialist agent handles this
    )

    @Serializable
    enum class WorkstreamType {
        COMMUNICATION, // Emails, Slack, notifications
        JIRA, // JIRA updates
        CODING, // Code changes
        ANALYSIS, // Read-only analysis
    }
}

class DiscoveryAgent(
    private val smartModelSelector: SmartModelSelector,
) {
    private val logger = KotlinLogging.logger {}

    data class DiscoveryInput(
        val triage: TriageAgent.TriageDocument,
        val missingFacts: List<String>,
    )

    fun create(promptExecutor: PromptExecutor): AIAgent<DiscoveryInput, DiscoveryDocument> {
        val toolRegistry =
            ToolRegistry {
                // TODO: Add discovery tools
            }

        val agentStrategy =
            strategy<DiscoveryInput, DiscoveryDocument>("Discovery: Gather evidence") {
                val nodePrompt by node<DiscoveryInput, String>("Build Prompt") { input ->
                    "Gather evidence for: ${input.missingFacts.joinToString(", ")}"
                }

                val nodePlaceholder by node<String, DiscoveryDocument>("Placeholder") { _ ->
                    logger.info { "DISCOVERY | executing" }
                    DiscoveryDocument(
                        evidenceGathered = listOf("Evidence"),
                        factsResolved = listOf(),
                        factsStillMissing = listOf(),
                        toolCallsMade = 0,
                    )
                }

                edge(nodeStart forwardTo nodePrompt)
                edge(nodePrompt forwardTo nodePlaceholder)
                edge(nodePlaceholder forwardTo nodeFinish)
            }

        val agentConfig =
            AIAgentConfig(
                prompt =
                    Prompt.build("discovery-agent") {
                        system("You gather evidence using tools.")
                    },
                model = smartModelSelector.selectModel(SmartModelSelector.BaseModelTypeEnum.AGENT),
                maxAgentIterations = 15,
            )

        return AIAgent(
            promptExecutor = promptExecutor,
            toolRegistry = toolRegistry,
            strategy = agentStrategy,
            agentConfig = agentConfig,
            installFeatures = {
                val logger = KotlinLogging.logger {}
                install(feature = EventHandler) {
                    onAgentStarting { ctx: AgentStartingContext ->
                        logger.info { "🚀 DiscoveryAgent STARTING | agentId=${ctx.agent.id}" }
                    }
                    onAgentCompleted { ctx: AgentCompletedContext ->
                        logger.info { "✅ DiscoveryAgent COMPLETED | result=${ctx.result}" }
                    }
                }
                install(feature = Tracing) {
                    addMessageProcessor(TraceFeatureMessageLogWriter(logger))
                }
            },
        )
    }

    @Serializable
    data class DiscoveryDocument(
        val evidenceGathered: List<String>,
        val factsResolved: List<String>,
        val factsStillMissing: List<String>,
        val toolCallsMade: Int,
    )
}

class ExecutionDispatcherAgent(
    private val smartModelSelector: SmartModelSelector,
) {
    private val logger = KotlinLogging.logger {}

    fun create(promptExecutor: PromptExecutor): AIAgent<DecompositionAgent.ExecutionPlan, DispatcherResult> {
        val toolRegistry =
            ToolRegistry {
                // TODO: Add specialist agent tools
            }

        val agentStrategy =
            strategy<DecompositionAgent.ExecutionPlan, DispatcherResult>("Dispatch: Execute workstreams") {
                val nodePrompt by node<DecompositionAgent.ExecutionPlan, String>("Build Prompt") { plan ->
                    "Execute ${plan.workstreams.size} workstreams"
                }

                val nodePlaceholder by node<String, DispatcherResult>("Placeholder") { _ ->
                    logger.info { "DISPATCHER | executing" }
                    DispatcherResult(
                        workstreamsExecuted = 0,
                        workstreamsSucceeded = 0,
                        workstreamsFailed = 0,
                        results = emptyList(),
                    )
                }

                edge(nodeStart forwardTo nodePrompt)
                edge(nodePrompt forwardTo nodePlaceholder)
                edge(nodePlaceholder forwardTo nodeFinish)
            }

        val agentConfig =
            AIAgentConfig(
                prompt =
                    Prompt.build("dispatcher-agent") {
                        system("You dispatch workstreams to specialist agents.")
                    },
                model = smartModelSelector.selectModel(SmartModelSelector.BaseModelTypeEnum.AGENT),
                maxAgentIterations = 20,
            )

        return AIAgent(
            promptExecutor = promptExecutor,
            toolRegistry = toolRegistry,
            strategy = agentStrategy,
            agentConfig = agentConfig,
            installFeatures = {
                val logger = KotlinLogging.logger {}
                install(feature = EventHandler) {
                    onAgentStarting { ctx: AgentStartingContext ->
                        logger.info { "🚀 ExecutionDispatcherAgent STARTING | agentId=${ctx.agent.id}" }
                    }
                    onAgentCompleted { ctx: AgentCompletedContext ->
                        logger.info {
                            "✅ ExecutionDispatcherAgent COMPLETED | result=${
                                ctx.result
                            }"
                        }
                    }
                }
                install(feature = Tracing) {
                    addMessageProcessor(TraceFeatureMessageLogWriter(logger))
                }
            },
        )
    }

    @Serializable
    data class DispatcherResult(
        val workstreamsExecuted: Int,
        val workstreamsSucceeded: Int,
        val workstreamsFailed: Int,
        val results: List<WorkstreamResult>,
    )

    @Serializable
    data class WorkstreamResult(
        val workstreamId: String,
        val success: Boolean,
        val output: String,
        val error: String? = null,
    )
}

/**
 * FinalizerAgent: Assembles final report for user.
 *
 * Responsibilities:
 * - Aggregate all workstream results
 * - Generate user-facing summary
 * - Create detailed report
 * - Include metadata
 */
class FinalizerAgent(
    private val smartModelSelector: SmartModelSelector,
) {
    private val logger = KotlinLogging.logger {}

    fun create(promptExecutor: PromptExecutor): AIAgent<ExecutionDispatcherAgent.DispatcherResult, OrchestratorResponse> {
        val toolRegistry =
            ToolRegistry {
                // NO TOOLS - pure aggregation
            }

        val agentStrategy =
            strategy<ExecutionDispatcherAgent.DispatcherResult, OrchestratorResponse>("Finalize: Assemble report") {
                val nodePrompt by node<ExecutionDispatcherAgent.DispatcherResult, String>("Build Prompt") { result ->
                    """
Assemble final report from workstream results.

Executed: ${result.workstreamsExecuted}
Succeeded: ${result.workstreamsSucceeded}
Failed: ${result.workstreamsFailed}

Results:
${result.results.joinToString("\n") { "- ${it.workstreamId}: ${it.output}" }}

Create:
1. User summary (1-2 sentences, what was done)
2. Detailed report (all actions, outcomes, next steps)
                    """.trimIndent()
                }

                val nodeFinalize by nodeLLMRequestStructured<OrchestratorResponse>(
                    name = "Extract OrchestratorResponse",
                    examples = emptyList(),
                )

                val nodeMapResult by node<Result<StructuredResponse<OrchestratorResponse>>, OrchestratorResponse>(
                    "Extract Result",
                ) { result ->
                    val response = result.getOrThrow().structure
                    logger.info { "FINALIZER | summary=${response.summary}..." }
                    response
                }

                edge(nodeStart forwardTo nodePrompt)
                edge(nodePrompt forwardTo nodeFinalize)
                edge(nodeFinalize forwardTo nodeMapResult)
                edge(nodeMapResult forwardTo nodeFinish)
            }

        val agentConfig =
            AIAgentConfig(
                prompt =
                    Prompt.build("finalizer-agent") {
                        system(
                            """
You are a finalizer. Aggregate workstream results into user-facing report.
Be concise in summary, detailed in full report.
                            """.trimIndent(),
                        )
                    },
                model = smartModelSelector.selectModel(SmartModelSelector.BaseModelTypeEnum.AGENT),
                maxAgentIterations = 3,
            )

        return AIAgent(
            promptExecutor = promptExecutor,
            toolRegistry = toolRegistry,
            strategy = agentStrategy,
            agentConfig = agentConfig,
            installFeatures = {
                val logger = KotlinLogging.logger {}
                install(feature = EventHandler) {
                    onAgentStarting { ctx: AgentStartingContext ->
                        logger.info { "🚀 FinalizerAgent STARTING | agentId=${ctx.agent.id}" }
                    }
                    onAgentCompleted { ctx: AgentCompletedContext ->
                        logger.info { "✅ FinalizerAgent COMPLETED | result=${ctx.result}" }
                    }
                }
                install(feature = Tracing) {
                    addMessageProcessor(TraceFeatureMessageLogWriter(logger))
                }
            },
        )
    }
}

/**
 * IntakeAgent: Analyzes raw user input and extracts structured information.
 *
 * Responsibilities:
 * - Parse intent (PROGRAMMING/QA/CHAT/UNKNOWN)
 * - Extract entities (JIRA keys, people, systems, repos)
 * - Identify requested outputs
 * - List unknowns that need discovery
 * - Extract constraints and communication targets
 *
 * Characteristics:
 * - No tools required (pure LLM structured output)
 * - Fast GPU model (small context ~16k)
 * - Minimal prompt focusing on schema
 */
class IntakeAgent(
    private val smartModelSelector: SmartModelSelector,
) {
    private val logger = KotlinLogging.logger {}

    fun create(promptExecutor: PromptExecutor): AIAgent<String, IntakeDocument> {
        val toolRegistry =
            ToolRegistry {
                // NO TOOLS - pure structured output
            }

        val agentStrategy =
            strategy<String, IntakeDocument>("Intake: Parse user request") {
                val nodeIntakePrompt by node<String, String>("Build Prompt") { userInput ->
                    """
Analyze user request and extract structured information.

Extract:
- Intent: PROGRAMMING/QA/CHAT/UNKNOWN
- JIRA keys (e.g., PROJ-123)
- Unknowns: what information is missing (NO guessing!)
- Urgency: CRITICAL/HIGH/NORMAL/LOW

User request:
$userInput
                    """.trimIndent()
                }

                val nodeIntakeStructured by nodeLLMRequestStructured<IntakeDocument>(
                    name = "Extract IntakeDocument",
                    examples =
                        listOf(
                            IntakeDocument(
                                intent = IntentType.PROGRAMMING,
                                jiraKeys = listOf("AUTH-123"),
                                unknowns = listOf("current auth implementation", "team email list"),
                                urgency = UrgencyLevel.HIGH,
                            ),
                        ),
                )

                val nodeMapResult by node<Result<StructuredResponse<IntakeDocument>>, IntakeDocument>(
                    "Extract Result",
                ) { result ->
                    val intake = result.getOrThrow().structure
                    logger.info { "INTAKE | intent=${intake.intent} | jiraKeys=${intake.jiraKeys.size} | urgency=${intake.urgency}" }
                    intake
                }

                edge(nodeStart forwardTo nodeIntakePrompt)
                edge(nodeIntakePrompt forwardTo nodeIntakeStructured)
                edge(nodeIntakeStructured forwardTo nodeMapResult)
                edge(nodeMapResult forwardTo nodeFinish)
            }

        val agentConfig =
            AIAgentConfig(
                prompt =
                    Prompt.build("intake-agent") {
                        system(
                            """
You are an intake analyzer. Extract structured information from user requests.
Follow the IntakeDocument schema exactly. Do NOT guess unknowns - list what's missing.
                            """.trimIndent(),
                        )
                    },
                model = smartModelSelector.selectModel(SmartModelSelector.BaseModelTypeEnum.AGENT),
                maxAgentIterations = 5,
            )

        return AIAgent(
            promptExecutor = promptExecutor,
            toolRegistry = toolRegistry,
            strategy = agentStrategy,
            agentConfig = agentConfig,
            installFeatures = {
                val logger = KotlinLogging.logger {}
                install(feature = EventHandler) {
                    onAgentStarting { ctx: AgentStartingContext ->
                        logger.info { "🚀 IntakeAgent STARTING | agentId=${ctx.agent.id}" }
                    }
                    onAgentCompleted { ctx: AgentCompletedContext ->
                        logger.info { "✅ IntakeAgent COMPLETED | result=${ctx.result}" }
                    }
                }
                install(feature = Tracing) {
                    addMessageProcessor(TraceFeatureMessageLogWriter(logger))
                }
            },
        )
    }
}

@LLMDescription("Analyze user request and extract structured intake information")
class IntakeAgentTool(
    private val promptExecutor: PromptExecutor,
    private val smartModelSelector: SmartModelSelector,
) : ToolSet {
    private val logger = KotlinLogging.logger {}

    @Tool
    @LLMDescription("Analyze user request and extract intent, entities, unknowns, constraints")
    suspend fun runIntakeAgent(
        @LLMDescription("Raw user request text")
        userRequest: String,
    ): IntakeDocument {
        logger.info { "runIntakeAgent | input=$userRequest" }
        val startTime = System.currentTimeMillis()

        val agent = IntakeAgent(smartModelSelector).create(promptExecutor)
        val result = agent.run(userRequest)

        val duration = System.currentTimeMillis() - startTime
        logger.info { "runIntakeAgent | duration=${duration}ms | intent=${result.intent}" }
        return result
    }
}

/**
 * JiraUpdateExecutionAgent: JIRA write operations.
 *
 * Tool allowlist:
 * - listTasks (read JIRA)
 * - updateTask (update issue)
 * - createTask (create issue)
 * - addComment (add comment)
 */
class JiraUpdateExecutionAgent(
    private val smartModelSelector: SmartModelSelector,
) {
    private val logger = KotlinLogging.logger {}

    fun create(promptExecutor: PromptExecutor): AIAgent<DecompositionAgent.Workstream, String> {
        val toolRegistry =
            ToolRegistry {
                // TODO: Add JIRA tools
            }

        val agentStrategy =
            strategy<DecompositionAgent.Workstream, String>("JIRA Execution") {
                val nodePrompt by node<DecompositionAgent.Workstream, String>("Build Prompt") { workstream ->
                    "Execute JIRA workstream: ${workstream.description}"
                }

                val nodePlaceholder by node<String, String>("Placeholder") { input ->
                    logger.info { "JIRA | $input" }
                    "JIRA updated (placeholder)"
                }

                edge(nodeStart forwardTo nodePrompt)
                edge(nodePrompt forwardTo nodePlaceholder)
                edge(nodePlaceholder forwardTo nodeFinish)
            }

        val agentConfig =
            AIAgentConfig(
                prompt =
                    Prompt.build("jira-execution-agent") {
                        system("You handle JIRA issue operations.")
                    },
                model = smartModelSelector.selectModel(SmartModelSelector.BaseModelTypeEnum.AGENT),
                maxAgentIterations = 10,
            )

        return AIAgent(
            promptExecutor = promptExecutor,
            toolRegistry = toolRegistry,
            strategy = agentStrategy,
            agentConfig = agentConfig,
            installFeatures = {
                val logger = KotlinLogging.logger {}
                install(feature = EventHandler) {
                    onAgentStarting { ctx: AgentStartingContext ->
                        logger.info { "🚀 JiraUpdateExecutionAgent STARTING | agentId=${ctx.agent.id}" }
                    }
                    onAgentCompleted { ctx: AgentCompletedContext ->
                        logger.info {
                            "✅ JiraUpdateExecutionAgent COMPLETED | result=${
                                ctx.result
                            }"
                        }
                    }
                }
                install(feature = Tracing) {
                    addMessageProcessor(TraceFeatureMessageLogWriter(logger))
                }
            },
        )
    }
}

/**
 * OrchestratorAgent: Lightweight coordinator for multi-agent workflow.
 *
 * Responsibilities:
 * - Accept user input
 * - Call IntakeAgent (parse request)
 * - Call ClientProjectScopeAgent (resolve context)
 * - Route to appropriate specialist agents based on intent
 * - Assemble final response
 *
 * Characteristics:
 * - Small, fast (GPU model)
 * - No heavy tool loops (delegates to specialist agents)
 * - Deterministic routing logic (Kotlin, not LLM guessing)
 * - Compact prompts (minimal context passing)
 */
class OrchestratorAgent(
    private val smartModelSelector: SmartModelSelector,
) {
    private val logger = KotlinLogging.logger {}

    fun <TArgs, TResult> create(
        promptExecutor: PromptExecutor,
        intakeAgentTool: IntakeAgentTool,
        scopeAgentTool: ClientProjectScopeAgentTool,
        additionalTools: List<ai.koog.agents.core.tools.Tool<TArgs, TResult>> = emptyList(),
        clientId: String,
    ): AIAgent<String, OrchestratorResponse> {
        val toolRegistry =
            ToolRegistry {
                tools(intakeAgentTool) // Reflection-based tool extraction
                tools(scopeAgentTool) // Reflection-based tool extraction
                if (additionalTools.isNotEmpty()) {
                    tools(additionalTools)
                }
            }

        val agentStrategy =
            strategy<String, OrchestratorResponse>("Orchestrator: Multi-agent workflow") {
                val intakeKey = createStorageKey<IntakeDocument>("intake")
                val scopeKey = createStorageKey<ProjectScopeSummary>("scope")

                // Phase 1: Intake
                val nodeCallIntake by node<String, String>("Build Intake Call") { userInput ->
                    """
Call runIntakeAgent to parse user request into structured IntakeDocument.

User request:
$userInput
                    """.trimIndent()
                }

                val nodeIntakeLLM by nodeLLMRequest("Intake LLM")

                val nodeIntakeExecute by nodeExecuteTool("Execute Intake Tool")

                val nodeIntakeSend by nodeLLMSendToolResult("Send Intake Result")

                val nodeExtractIntake by node<Unit, IntakeDocument>("Extract Intake") {
                    val intake = storage.get(intakeKey)
                    if (intake != null) {
                        intake
                    } else {
                        logger.warn { "ORCHESTRATOR | No intake found in storage, using fallback" }
                        IntakeDocument(
                            intent = IntentType.UNKNOWN,
                        )
                    }
                }

                val nodeStoreIntake by node<IntakeDocument, IntakeDocument>("Store Intake") { intake ->
                    storage.set(intakeKey, intake)
                    logger.info { "ORCHESTRATOR | Intake stored | intent=${intake.intent}" }
                    intake
                }

                // Phase 2: Scope Resolution
                val nodeCallScope by node<IntakeDocument, String>("Build Scope Call") { intake ->
                    """
Call runScopeAgent to resolve project context.

IntakeDocument:
- Intent: ${intake.intent}
- JIRA keys: ${intake.jiraKeys.joinToString()}

Client ID: $clientId
                    """.trimIndent()
                }

                val nodeScopeLLM by nodeLLMRequest("Scope LLM")

                val nodeScopeExecute by nodeExecuteTool("Execute Scope Tool")

                val nodeScopeSend by nodeLLMSendToolResult("Send Scope Result")

                val nodeExtractScope by node<Unit, ProjectScopeSummary>("Extract Scope") {
                    val scope = storage.get(scopeKey)
                    if (scope != null) {
                        scope
                    } else {
                        logger.warn { "ORCHESTRATOR | No scope found in storage, using fallback" }
                        ProjectScopeSummary(
                            projects = emptyList(),
                            certainty = ProjectCertainty.UNKNOWN,
                        )
                    }
                }

                val nodeStoreScope by node<ProjectScopeSummary, ProjectScopeSummary>("Store Scope") { scope ->
                    storage.set(scopeKey, scope)
                    logger.info { "ORCHESTRATOR | Scope stored | projects=${scope.projects.size} | certainty=${scope.certainty}" }
                    scope
                }

                // Phase 3: Final Assembly (simplified for now)
                val nodeBuildResponse by node<ProjectScopeSummary, OrchestratorResponse>("Build Response") { scope ->
                    val intake = storage.get(intakeKey)!!

                    OrchestratorResponse(
                        summary = "Request processed: ${intake.intent}. Found ${scope.projects.size} relevant projects.",
                        intent = intake.intent,
                        projects = scope.projects,
                    )
                }

                // Graph wiring
                edge(nodeStart forwardTo nodeCallIntake)
                edge(nodeCallIntake forwardTo nodeIntakeLLM)
                edge(
                    nodeIntakeLLM forwardTo nodeIntakeExecute
                        onToolCall { true },
                )
                edge(nodeIntakeExecute forwardTo nodeIntakeSend)
                edge(
                    nodeIntakeSend forwardTo nodeExtractIntake
                        transformed { },
                )
                edge(nodeExtractIntake forwardTo nodeStoreIntake)

                edge(nodeStoreIntake forwardTo nodeCallScope)
                edge(nodeCallScope forwardTo nodeScopeLLM)
                edge(
                    nodeScopeLLM forwardTo nodeScopeExecute
                        onToolCall { true },
                )
                edge(nodeScopeExecute forwardTo nodeScopeSend)
                edge(
                    nodeScopeSend forwardTo nodeExtractScope
                        transformed { },
                )
                edge(nodeExtractScope forwardTo nodeStoreScope)

                edge(nodeStoreScope forwardTo nodeBuildResponse)
                edge(nodeBuildResponse forwardTo nodeFinish)
            }

        val agentConfig =
            AIAgentConfig(
                prompt =
                    Prompt.build("orchestrator-agent") {
                        system(
                            """
You are an orchestrator. Your job is to coordinate specialist agents.

Workflow:
1. Call runIntakeAgent with user request
2. Call runScopeAgent with intake result and clientId
3. Route to specialist agents based on intent (future)
4. Assemble final response

Keep prompts minimal. Delegate heavy work to specialist agents.
                            """.trimIndent(),
                        )
                    },
                model = smartModelSelector.selectModel(SmartModelSelector.BaseModelTypeEnum.AGENT),
                maxAgentIterations = 20,
            )

        return AIAgent(
            promptExecutor = promptExecutor,
            toolRegistry = toolRegistry,
            strategy = agentStrategy,
            agentConfig = agentConfig,
            installFeatures = {
                val logger = KotlinLogging.logger {}
                install(feature = EventHandler) {
                    onAgentStarting { ctx: AgentStartingContext ->
                        logger.info { "🚀 OrchestratorAgent STARTING | agentId=${ctx.agent.id}" }
                    }
                    onAgentCompleted { ctx: AgentCompletedContext ->
                        logger.info { "✅ OrchestratorAgent COMPLETED | result=${ctx.result}" }
                    }
                }
                install(feature = Tracing) {
                    addMessageProcessor(TraceFeatureMessageLogWriter(logger))
                }
            },
        )
    }
}

/**
 * TechAnalysisExecutionAgent: Read-only file analysis.
 *
 * Tool allowlist:
 * - readFile
 * - listDirectory
 * - ragSearch
 * - graphQuery
 * NO write operations
 */
class TechAnalysisExecutionAgent(
    private val smartModelSelector: SmartModelSelector,
) {
    private val logger = KotlinLogging.logger {}

    fun create(promptExecutor: PromptExecutor): AIAgent<DecompositionAgent.Workstream, String> {
        val toolRegistry =
            ToolRegistry {
                // TODO: Add read-only tools
            }

        val agentStrategy =
            strategy<DecompositionAgent.Workstream, String>("Analysis Execution") {
                val nodePrompt by node<DecompositionAgent.Workstream, String>("Build Prompt") { workstream ->
                    "Execute analysis workstream: ${workstream.description}"
                }

                val nodePlaceholder by node<String, String>("Placeholder") { input ->
                    logger.info { "ANALYSIS | $input" }
                    "Analysis completed (placeholder)"
                }

                edge(nodeStart forwardTo nodePrompt)
                edge(nodePrompt forwardTo nodePlaceholder)
                edge(nodePlaceholder forwardTo nodeFinish)
            }

        val agentConfig =
            AIAgentConfig(
                prompt =
                    Prompt.build("analysis-execution-agent") {
                        system("You perform read-only technical analysis.")
                    },
                model = smartModelSelector.selectModel(SmartModelSelector.BaseModelTypeEnum.AGENT),
                maxAgentIterations = 10,
            )

        return AIAgent(
            promptExecutor = promptExecutor,
            toolRegistry = toolRegistry,
            strategy = agentStrategy,
            agentConfig = agentConfig,
            installFeatures = {
                val logger = KotlinLogging.logger {}
                install(feature = EventHandler) {
                    onAgentStarting { ctx: AgentStartingContext ->
                        logger.info { "🚀 TechAnalysisExecutionAgent STARTING | agentId=${ctx.agent.id}" }
                    }
                    onAgentCompleted { ctx: AgentCompletedContext ->
                        logger.info {
                            "✅ TechAnalysisExecutionAgent COMPLETED | result=${
                                ctx.result
                            }"
                        }
                    }
                }
                install(feature = Tracing) {
                    addMessageProcessor(TraceFeatureMessageLogWriter(logger))
                }
            },
        )
    }
}

/**
 * TriageAgent: Identifies minimal mandatory facts needed before proceeding.
 *
 * Responsibilities:
 * - Decide: Can we proceed immediately OR need more facts?
 * - Identify minimal set of missing facts (not exhaustive)
 * - Determine which tools are needed for discovery
 * - Assess risk/confidence level
 *
 * Characteristics:
 * - Minimal tool allowlist (no execution tools)
 * - Fast decision making (GPU_FAST model)
 * - Evidence-driven (Kotlin routing, not LLM guessing)
 */
class TriageAgent(
    private val smartModelSelector: SmartModelSelector,
) {
    private val logger = KotlinLogging.logger {}

    data class TriageInput(
        val intake: IntakeDocument,
        val scope: ProjectScopeSummary,
    )

    fun create(promptExecutor: PromptExecutor): AIAgent<TriageInput, TriageDocument> {
        val toolRegistry =
            ToolRegistry {
                // Minimal tools: no writes, no executions
            }

        val agentStrategy =
            strategy<TriageInput, TriageDocument>("Triage: Identify missing facts") {
                val nodePrompt by node<TriageInput, String>("Build Prompt") { input ->
                    """
Analyze intake and scope to identify minimal missing facts.

Intent: ${input.intake.intent}
JIRA keys: ${input.intake.jiraKeys}
Unknowns: ${input.intake.unknowns}
Urgency: ${input.intake.urgency}
Projects: ${input.scope.projects}
Scope certainty: ${input.scope.certainty}

Determine:
1. Can we proceed immediately? (all critical facts known)
2. What minimal facts are REQUIRED? (not exhaustive)
3. Which discovery tools needed?
4. Confidence level and risk assessment
                    """.trimIndent()
                }

                val nodeTriageStructured by nodeLLMRequestStructured<TriageDocument>(
                    name = "Extract TriageDocument",
                    examples =
                        listOf(
                            TriageDocument(
                                canProceed = false,
                                missingFacts = listOf("AUTH-123 current status", "team email addresses"),
                                discoveryToolsNeeded = listOf("jiraQuery", "graphQuery"),
                                confidenceLevel = ConfidenceLevel.LOW,
                                riskAssessment = "Cannot proceed without JIRA context",
                            ),
                        ),
                )

                val nodeMapResult by node<Result<StructuredResponse<TriageDocument>>, TriageDocument>(
                    "Extract Result",
                ) { result ->
                    val triage = result.getOrThrow().structure
                    logger.info { "TRIAGE | canProceed=${triage.canProceed} | missing=${triage.missingFacts.size} facts" }
                    triage
                }

                edge(nodeStart forwardTo nodePrompt)
                edge(nodePrompt forwardTo nodeTriageStructured)
                edge(nodeTriageStructured forwardTo nodeMapResult)
                edge(nodeMapResult forwardTo nodeFinish)
            }

        val agentConfig =
            AIAgentConfig(
                prompt =
                    Prompt.build("triage-agent") {
                        system(
                            """
You are a triage agent. Identify minimal missing facts REQUIRED for execution.
Do NOT create exhaustive lists - only critical blockers.
Use evidence-driven reasoning: if fact is in scope/intake, it's NOT missing.
                            """.trimIndent(),
                        )
                    },
                model = smartModelSelector.selectModel(SmartModelSelector.BaseModelTypeEnum.AGENT),
                maxAgentIterations = 3,
            )

        return AIAgent(
            promptExecutor = promptExecutor,
            toolRegistry = toolRegistry,
            strategy = agentStrategy,
            agentConfig = agentConfig,
            installFeatures = {
                val logger = KotlinLogging.logger {}
                install(feature = EventHandler) {
                    onAgentStarting { ctx: AgentStartingContext ->
                        logger.info { "🚀 TriageAgent STARTING | agentId=${ctx.agent.id}" }
                    }
                    onAgentCompleted { ctx: AgentCompletedContext ->
                        logger.info { "✅ TriageAgent COMPLETED | result=${ctx.result}" }
                    }
                }
                install(feature = Tracing) {
                    addMessageProcessor(TraceFeatureMessageLogWriter(logger))
                }
            },
        )
    }

    @Serializable
    enum class ConfidenceLevel {
        HIGH,
        MEDIUM,
        LOW,
    }

    @Serializable
    data class TriageDocument(
        val canProceed: Boolean,
        val missingFacts: List<String>,
        val discoveryToolsNeeded: List<String>,
        val confidenceLevel: ConfidenceLevel,
        val riskAssessment: String,
    )
}
