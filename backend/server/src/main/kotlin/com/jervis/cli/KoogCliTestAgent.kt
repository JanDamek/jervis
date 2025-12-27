package com.jervis.cli

import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.feature.handler.agent.AgentCompletedContext
import ai.koog.agents.core.feature.handler.agent.AgentStartingContext
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.core.tools.reflect.tools
import ai.koog.agents.ext.tool.file.EditFileTool
import ai.koog.agents.ext.tool.file.ListDirectoryTool
import ai.koog.agents.ext.tool.file.ReadFileTool
import ai.koog.agents.ext.tool.file.WriteFileTool
import ai.koog.agents.ext.tool.shell.ExecuteShellCommandTool
import ai.koog.agents.ext.tool.shell.JvmShellCommandExecutor
import ai.koog.agents.ext.tool.shell.PrintShellCommandConfirmationHandler
import ai.koog.agents.features.eventHandler.feature.EventHandler
import ai.koog.agents.planner.AIAgentPlannerStrategy
import ai.koog.agents.planner.PlannerAIAgent
import ai.koog.agents.planner.goap.GOAPPlan
import ai.koog.agents.planner.goap.goap
import ai.koog.prompt.dsl.Prompt
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.rag.base.files.JVMFileSystemProvider
import com.jervis.common.client.IAiderClient
import com.jervis.common.client.ICodingEngineClient
import com.jervis.dto.TaskStateEnum
import com.jervis.dto.TaskTypeEnum
import com.jervis.entity.TaskDocument
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
import kotlin.reflect.typeOf

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
) {
    private val logger = KotlinLogging.logger {}

    fun create(
        promptExecutor: PromptExecutor,
        clientId: String,
    ): PlannerAIAgent<OrchestratorState, GOAPPlan<OrchestratorState>> {
        val testCliTask =
            TaskDocument(
                type = TaskTypeEnum.USER_INPUT_PROCESSING,
                content = "CLI Test Task",
                clientId = ClientId(ObjectId(clientId)),
                projectId = null,
                sourceUrn = SourceUrn("cli://test"),
                state = TaskStateEnum.NEW,
            )

        val toolRegistry =
            ToolRegistry {
                tools(KnowledgeStorageTools(testCliTask, knowledgeService, graphDBService))

                tools(
                    QualifierRoutingTools(
                        testCliTask,
                        taskService,
                        linkContentService,
                        indexedLinkService,
                        connectionService,
                    ),
                )

                tools(SchedulerTools(testCliTask, taskManagementService))

                tools(UserInteractionTools(testCliTask, userTaskService))

                tools(ProjectDiscoveryTools(testCliTask, projectRepository))

                tools(CommunicationTools(testCliTask))

                tools(
                    listOf(
                        ListDirectoryTool(JVMFileSystemProvider.ReadOnly),
                        ReadFileTool(JVMFileSystemProvider.ReadOnly),
                        EditFileTool(JVMFileSystemProvider.ReadWrite),
                        WriteFileTool(JVMFileSystemProvider.ReadWrite),
                        ExecuteShellCommandTool(JvmShellCommandExecutor(), PrintShellCommandConfirmationHandler()),
                    ),
                )

                tools(AiderCodingTool(testCliTask, aiderClient))
                tools(OpenHandsCodingTool(testCliTask, codingEngineClient))
            }

        // GOAP planner for orchestration workflow
        val planner =
            goap<OrchestratorState>(typeOf<OrchestratorState>()) {
                // Action 1: Analyze user request
                action(
                    name = "AnalyzeRequest",
                    precondition = { state -> !state.requestAnalyzed },
                    belief = { state -> state.copy(requestAnalyzed = true) },
                    cost = { 1.0 },
                ) { ctx, state ->
                    logger.info { "GOAP_ACTION: Analyzing user request" }

                    ctx.llm.writeSession {
                        appendPrompt {
                            user {
                                """Analyze this user request and extract structured information:

${state.userInput}

Identify:
- Intent (PROGRAMMING/QA/CHAT/UNKNOWN)
- JIRA keys, systems, entities
- What information is missing
- Required actions"""
                            }
                        }
                        requestLLM()
                    }

                    state.copy(requestAnalyzed = true)
                }

                // Action 2: Gather evidence and execute
                action(
                    name = "ExecuteTask",
                    precondition = { state -> state.requestAnalyzed && !state.taskExecuted },
                    belief = { state -> state.copy(taskExecuted = true) },
                    cost = { 2.0 },
                ) { ctx, state ->
                    logger.info { "GOAP_ACTION: Executing task with available tools" }

                    ctx.llm.writeSession {
                        appendPrompt {
                            user {
                                """Execute the task using available tools.

User request: ${state.userInput}

Available actions:
- Use discovery tools to gather facts
- Use coding tools for code changes
- Use communication tools for emails/Slack
- Use project tools to resolve context
- Use RAG/Graph for knowledge search

Execute all necessary actions to complete the task."""
                            }
                        }
                        requestLLM()
                    }

                    state.copy(taskExecuted = true)
                }

                // Action 3: Provide a final response
                action(
                    name = "FinalizeResponse",
                    precondition = { state -> state.taskExecuted && !state.responseGenerated },
                    belief = { state -> state.copy(responseGenerated = true) },
                    cost = { 1.0 },
                ) { ctx, state ->
                    logger.info { "GOAP_ACTION: Generating final response" }

                    ctx.llm.writeSession {
                        appendPrompt {
                            user {
                                +"Provide final summary of what was done."
                                +"Include:"
                                +"- Actions taken"
                                +"- Results achieved"
                                +"- Any issues or next steps"
                                +"Be concise and factual."
                            }
                        }
                        requestLLM()
                    }

                    state.copy(responseGenerated = true)
                }

                // Goals
                goal(
                    name = "TaskCompleted",
                    description = "Task analyzed, executed, and response provided",
                    condition = { state -> state.taskExecuted && state.responseGenerated },
                )
            }

        val strategy = AIAgentPlannerStrategy("cli-orchestrator-planner", planner)
        val agentConfig =
            AIAgentConfig(
                prompt =
                    Prompt.build("orchestrator-agent") {
                        system {
                            +"""You are an orchestrator coordinating tools and agents.

Your goal: Solve user requests end-to-end using available tools.

Workflow:
1. Analyze the request - understand intent and requirements
2. Execute necessary actions - use tools to gather facts and perform work
3. Provide clear summary of results

LoggingLevel.Critical rules:
- Always cite sources (JIRA keys, files, graph nodes)
- NO assumptions - use discovery tools when information is missing
- For communications, draft first and get confirmation
- Use evidence-driven approach

Use tools to accomplish tasks, do not explain - execute."""
                        }
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

        return PlannerAIAgent(
            promptExecutor = promptExecutor,
            strategy = strategy,
            agentConfig = agentConfig,
            toolRegistry = toolRegistry,
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
            },
        )
    }

    @Serializable
    data class OrchestratorState(
        val userInput: String,
        val requestAnalyzed: Boolean = false,
        val taskExecuted: Boolean = false,
        val responseGenerated: Boolean = false,
    )
}
