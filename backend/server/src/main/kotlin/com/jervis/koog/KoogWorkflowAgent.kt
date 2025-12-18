package com.jervis.koog

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.dsl.builder.forwardTo
import ai.koog.agents.core.dsl.builder.strategy
import ai.koog.agents.core.dsl.extension.nodeExecuteTool
import ai.koog.agents.core.dsl.extension.nodeLLMCompressHistory
import ai.koog.agents.core.dsl.extension.nodeLLMRequest
import ai.koog.agents.core.dsl.extension.nodeLLMSendToolResult
import ai.koog.agents.core.dsl.extension.onAssistantMessage
import ai.koog.agents.core.dsl.extension.onToolCall
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.core.tools.reflect.tools
import ai.koog.agents.ext.tool.file.EditFileTool
import ai.koog.agents.ext.tool.file.ListDirectoryTool
import ai.koog.agents.ext.tool.file.ReadFileTool
import ai.koog.agents.ext.tool.file.WriteFileTool
import ai.koog.agents.ext.tool.shell.ExecuteShellCommandTool
import ai.koog.agents.ext.tool.shell.JvmShellCommandExecutor
import ai.koog.agents.ext.tool.shell.PrintShellCommandConfirmationHandler
import ai.koog.prompt.dsl.Prompt
import ai.koog.rag.base.files.JVMFileSystemProvider
import com.jervis.common.client.IAiderClient
import com.jervis.common.client.ICodingEngineClient
import com.jervis.configuration.properties.KoogProperties
import com.jervis.entity.PendingTaskDocument
import com.jervis.graphdb.GraphDBService
import com.jervis.graphdb.model.Direction
import com.jervis.koog.tools.AiderCodingTool
import com.jervis.koog.tools.CommunicationTools
import com.jervis.koog.tools.GraphTools
import com.jervis.koog.tools.OpenHandsCodingTool
import com.jervis.koog.tools.RagTools
import com.jervis.koog.tools.TaskTools
import com.jervis.rag.KnowledgeService
import com.jervis.rag.SearchRequest
import com.jervis.service.background.PendingTaskService
import com.jervis.service.link.IndexedLinkService
import com.jervis.service.link.LinkContentService
import com.jervis.service.scheduling.TaskManagementService
import com.jervis.service.task.UserTaskService
import com.jervis.service.token.TokenCountingService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import mu.KotlinLogging
import org.springframework.stereotype.Service
import java.util.concurrent.ConcurrentHashMap

/**
 * KoogWorkflowAgent - modular multi-subgraph architecture for JERVIS workflows.
 *
 * NEW ARCHITECTURE (Graph-Based Routing):
 * - GPU-based workflow agent for complex analysis and actions
 * - Receives tasks routed from KoogQualifierAgent (CPU) via READY_FOR_GPU state
 * - Loads structured context from TaskMemory instead of re-reading documents
 * - Focus on analysis and actions, not data structuring (already done by Qualifier)
 *
 * Architecture:
 * - Parallel enrichment (RAG + Graph simultaneously)
 * - ResearchSubgraph: information gathering phase (can load TaskMemory context)
 * - AnalysisSubgraph: decision-making phase
 * - ExecutionSubgraph: action execution phase (files, shell, tasks, communication)
 * - StorageSubgraph: persist results to RAG/Graph/Memory
 * - History compression in Research/Analysis (50 msgs), Execution (30 msgs)
 * - Sequential flow through all subgraphs
 * - All tools registered globally in ToolRegistry, available to all subgraphs
 *
 * NEW Tool: TaskMemoryTool
 * - Loads context prepared by Qualifier (findings, action items, Graph/RAG references)
 * - Avoids redundant work - data already structured
 * - Enables efficient GPU usage by skipping structuring phase
 */
@Service
class KoogWorkflowAgent(
    private val knowledgeService: KnowledgeService,
    private val taskManagementService: TaskManagementService,
    private val userTaskService: UserTaskService,
    private val promptExecutorFactory: KoogPromptExecutorFactory,
    private val koogProperties: KoogProperties,
    private val pendingTaskService: PendingTaskService,
    private val linkContentService: LinkContentService,
    private val indexedLinkService: IndexedLinkService,
    private val smartModelSelector: SmartModelSelector,
    private val tokenCountingService: TokenCountingService,
    private val graphDBService: GraphDBService,
    private val aiderClient: IAiderClient,
    private val codingEngineClient: ICodingEngineClient,
    private val connectionService: com.jervis.service.connection.ConnectionService,
) {
    private val logger = KotlinLogging.logger {}
    private val activeAgents = ConcurrentHashMap<String, String>()

    private val supervisor = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + supervisor)

    fun isProviderInUse(providerName: String): Boolean = activeAgents.containsValue(providerName)

    data class EnrichmentContext(
        val ragContext: String,
        val graphContext: String,
        val originalLanguage: String,
    )

    suspend fun create(task: PendingTaskDocument): AIAgent<String, String> {
        val promptExecutor = promptExecutorFactory.getExecutor("OLLAMA")

        val agentStrategy =
            strategy("JERVIS modular workflow") {
                // =================================================================
                // PARALLEL ENRICHMENT - RAG + Graph simultaneously
                // =================================================================
                val nodeParallelEnrich by node<String, EnrichmentContext> { input ->
                    val ragResult =
                        try {
                            val query = input.take(500)
                            val searchRequest =
                                SearchRequest(
                                    query = query,
                                    clientId = task.clientId,
                                    projectId = task.projectId,
                                    maxResults = 15,
                                    minScore = 0.55,
                                )
                            knowledgeService.search(searchRequest).text
                        } catch (_: Exception) {
                            ""
                        }

                    val graphResult =
                        try {
                            val projectKey = "project::${task.projectId ?: "none"}"
                            val nodes =
                                graphDBService.getRelated(
                                    clientId = task.clientId,
                                    nodeKey = projectKey,
                                    edgeTypes = emptyList(),
                                    direction = Direction.ANY,
                                    limit = 20,
                                )
                            if (nodes.isEmpty()) {
                                ""
                            } else {
                                buildString {
                                    appendLine("Graph related nodes (summary):")
                                    nodes.take(20).forEach { n ->
                                        append("- ").append(n.key)
                                        appendLine()
                                    }
                                }
                            }
                        } catch (_: Exception) {
                            ""
                        }

                    EnrichmentContext(
                        ragContext = ragResult,
                        graphContext = graphResult,
                        originalLanguage = "EN", // TODO detect original languahge in prompt
                    )
                }

                val nodePrepareInput by node<EnrichmentContext, String> { ctx ->
                    buildString {
                        appendLine("TARGET_LANGUAGE: ${ctx.originalLanguage}")
                        if (ctx.ragContext.isNotBlank()) {
                            appendLine("\n[RAG]\n${ctx.ragContext}")
                        }
                        if (ctx.graphContext.isNotBlank()) {
                            appendLine("\n[GRAPH]\n${ctx.graphContext}")
                        }
                        appendLine("\n[USER_INPUT]\n${llm.readSession { prompt.messages.firstOrNull()?.content ?: "" }}")
                    }.trim()
                }

                val researchSubgraph by subgraph<String, String>(
                    name = "research",
                ) {
                    val nodeSendInput by nodeLLMRequest()
                    val nodeExecuteTool by nodeExecuteTool()
                    val nodeSendToolResult by nodeLLMSendToolResult()
                    val nodeCompressHistory by nodeLLMCompressHistory()

                    edge(nodeStart forwardTo nodeSendInput)
                    edge((nodeSendInput forwardTo nodeFinish).onAssistantMessage { true })
                    edge((nodeSendInput forwardTo nodeExecuteTool).onToolCall { true })

                    // Compress history if too large
                    edge(
                        (nodeExecuteTool forwardTo nodeCompressHistory)
                            .onCondition { llm.readSession { prompt.messages.size > 50 } },
                    )
                    edge(nodeCompressHistory forwardTo nodeSendToolResult)

                    edge(
                        (nodeExecuteTool forwardTo nodeSendToolResult)
                            .onCondition { llm.readSession { prompt.messages.size <= 50 } },
                    )

                    edge((nodeSendToolResult forwardTo nodeFinish).onAssistantMessage { true })
                    edge((nodeSendToolResult forwardTo nodeExecuteTool).onToolCall { true })
                }

                val analysisSubgraph by subgraph<String, String>(
                    name = "analysis",
                ) {
                    val nodeSendInput by nodeLLMRequest()
                    val nodeExecuteTool by nodeExecuteTool()
                    val nodeSendToolResult by nodeLLMSendToolResult()
                    val nodeCompressHistory by nodeLLMCompressHistory()

                    edge(nodeStart forwardTo nodeSendInput)
                    edge((nodeSendInput forwardTo nodeFinish).onAssistantMessage { true })
                    edge((nodeSendInput forwardTo nodeExecuteTool).onToolCall { true })

                    edge(
                        (nodeExecuteTool forwardTo nodeCompressHistory)
                            .onCondition { llm.readSession { prompt.messages.size > 50 } },
                    )
                    edge(nodeCompressHistory forwardTo nodeSendToolResult)

                    edge(
                        (nodeExecuteTool forwardTo nodeSendToolResult)
                            .onCondition { llm.readSession { prompt.messages.size <= 50 } },
                    )

                    edge((nodeSendToolResult forwardTo nodeFinish).onAssistantMessage { true })
                    edge((nodeSendToolResult forwardTo nodeExecuteTool).onToolCall { true })
                }

                val executionSubgraph by subgraph<String, String>(
                    name = "execution",
                ) {
                    val nodeSendInput by nodeLLMRequest()
                    val nodeExecuteTool by nodeExecuteTool()
                    val nodeSendToolResult by nodeLLMSendToolResult()
                    val nodeCompressHistory by nodeLLMCompressHistory()

                    edge(nodeStart forwardTo nodeSendInput)
                    edge((nodeSendInput forwardTo nodeFinish).onAssistantMessage { true })
                    edge((nodeSendInput forwardTo nodeExecuteTool).onToolCall { true })

                    // More aggressive compression for execution (large outputs)
                    edge(
                        (nodeExecuteTool forwardTo nodeCompressHistory)
                            .onCondition { llm.readSession { prompt.messages.size > 30 } },
                    )
                    edge(nodeCompressHistory forwardTo nodeSendToolResult)

                    edge(
                        (nodeExecuteTool forwardTo nodeSendToolResult)
                            .onCondition { llm.readSession { prompt.messages.size <= 30 } },
                    )

                    edge((nodeSendToolResult forwardTo nodeFinish).onAssistantMessage { true })
                    edge((nodeSendToolResult forwardTo nodeExecuteTool).onToolCall { true })
                }

                val storageSubgraph by subgraph<String, String>(
                    name = "storage",
                ) {
                    val nodeSendInput by nodeLLMRequest()
                    val nodeExecuteTool by nodeExecuteTool()
                    val nodeSendToolResult by nodeLLMSendToolResult()

                    edge(nodeStart forwardTo nodeSendInput)
                    edge((nodeSendInput forwardTo nodeFinish).onAssistantMessage { true })
                    edge((nodeSendInput forwardTo nodeExecuteTool).onToolCall { true })
                    edge(nodeExecuteTool forwardTo nodeSendToolResult)
                    edge((nodeSendToolResult forwardTo nodeFinish).onAssistantMessage { true })
                    edge((nodeSendToolResult forwardTo nodeExecuteTool).onToolCall { true })
                }

                edge(nodeStart forwardTo nodeParallelEnrich)
                edge(nodeParallelEnrich forwardTo nodePrepareInput)

                edge((nodePrepareInput forwardTo researchSubgraph).transformed { it })
                edge((researchSubgraph forwardTo analysisSubgraph).transformed { it })
                edge((analysisSubgraph forwardTo executionSubgraph).transformed { it })
                edge((executionSubgraph forwardTo storageSubgraph).transformed { it })
                edge((storageSubgraph forwardTo nodeFinish).transformed { it })
            }

        // Dynamic model selection based on task content length
        // SmartModelSelector uses exact BPE token counting (jtokkit)
        // Workflow agent generates analysis, code, and actions - needs more output than input
        // Formula: output ≈ 2x input (analysis + actions can be verbose)
        val inputTokens = tokenCountingService.countTokens(task.content)
        val dynamicOutputReserve = (inputTokens * 2).coerceAtLeast(4000)

        val dynamicModel =
            smartModelSelector.selectModel(
                baseModelName = MODEL_WORKFLOW_NAME, // Base: qwen3-coder-tool:30b
                inputContent = task.content,
                outputReserve = dynamicOutputReserve, // Dynamic: 2x input tokens
            )

        logger.info {
            "KoogWorkflowAgent | Dynamic model selected: ${dynamicModel.id} | " +
                "contextLength=${dynamicModel.contextLength} | " +
                "taskContentLength=${task.content.length} | " +
                "inputTokens=$inputTokens | " +
                "outputReserve=$dynamicOutputReserve | " +
                "baseModel=$MODEL_WORKFLOW_NAME"
        }

        val agentConfig =
            AIAgentConfig(
                prompt =
                    Prompt.build("jervis-agent") {
                        system(
                            """
                            You are JERVIS Agent - an autonomous workflow agent using Koog framework.

                            WORKFLOW PHASES:
                            1. Research: Gather information (RAG search, Graph query, File read)
                            2. Analysis: Make decisions (RAG, Graph, Memory operations)
                            3. Execution: Take actions (File write, Shell, Tasks, Communication)
                            4. Storage: Persist results (RAG store, Graph upsert, Memory write)

                            CAPABILITIES:
                            - RAG: Search and store knowledge with semantic embeddings
                            - Graph: Query and upsert nodes/edges in knowledge graph
                            - Memory: Read/write structured agent memory with tags
                            - Files: Read, write, edit files with safety checks
                            - Shell: Execute system commands when needed
                            - Tasks: Schedule tasks, create user tasks, dialogs
                            - Communication: Email, Slack, Teams (mock for now)
                            - System: Index links, create background analysis tasks
                            - Coding Tools: Aider (local surgical edits) and OpenHands (heavy isolated sandbox)

                            CODING TOOLS - MODEL SELECTION:
                            You have access to coding tools (Aider, OpenHands) that run on fast local models (Qwen) by default.

                            WHEN TO USE PAID MODEL (set model='paid'):
                            - Architecturally complex tasks requiring deep design knowledge
                            - Security-critical code that needs thorough analysis
                            - Large-scale refactoring across multiple modules
                            - Complex business logic or algorithms
                            - Production-critical changes

                            WHEN TO USE DEFAULT (leave model empty):
                            - Standard CRUD operations
                            - Writing tests
                            - Bug fixes
                            - Getters/setters, DTOs, mappers
                            - Simple refactoring
                            - Documentation updates

                            GUIDELINES:
                            - Store important findings as MEMORY for future reference
                            - Create user tasks for items requiring human action
                            - Always respond in TARGET_LANGUAGE from context
                            - Be concise and actionable
                            - For code changes: prefer Aider for small, file-scoped fixes/refactors when target files are known;
                              use OpenHands for large tasks, running/debugging apps, or installing dependencies (K8s sandbox).
                            - If Aider returns a jobId (async mode), poll progress using checkAiderStatus(jobId).
                            """.trimIndent(),
                        )
                    },
                model = dynamicModel, // Dynamic model from SmartModelSelector
                maxAgentIterations = koogProperties.maxIterations,
            )

        val toolRegistry =
            ToolRegistry {
                tools(
                    listOf(
                        ListDirectoryTool(JVMFileSystemProvider.ReadOnly),
                        ReadFileTool(JVMFileSystemProvider.ReadOnly),
                        EditFileTool(JVMFileSystemProvider.ReadWrite),
                        WriteFileTool(JVMFileSystemProvider.ReadWrite),
                    ),
                )

                tools(
                    listOf(
                        ExecuteShellCommandTool(
                            JvmShellCommandExecutor(),
                            PrintShellCommandConfirmationHandler(),
                        ),
                    ),
                )

                tools(RagTools(task, knowledgeService))
                tools(GraphTools(task, graphDBService))
                tools(
                    TaskTools(
                        task = task,
                        taskManagementService = taskManagementService,
                        userTaskService = userTaskService,
                        pendingTaskService = pendingTaskService,
                        linkContentService = linkContentService,
                        indexedLinkService = indexedLinkService,
                        connectionService = connectionService,
                        coroutineScope = scope,
                    ),
                )
                tools(CommunicationTools(task))
                tools(AiderCodingTool(task, aiderClient))
                tools(OpenHandsCodingTool(task, codingEngineClient))
            }

        return AIAgent(
            promptExecutor = promptExecutor,
            toolRegistry = toolRegistry,
            strategy = agentStrategy,
            agentConfig = agentConfig,
        )
    }

    suspend fun run(
        task: PendingTaskDocument,
        userInput: String,
    ): String {
        val startTime = System.currentTimeMillis()
        val provider = "OLLAMA" // This agent always uses the main OLLAMA provider

        logger.info {
            "🟢 KOOG_WORKFLOW_START | " +
                "correlationId=${task.correlationId} | " +
                "clientId=${task.clientId} | " +
                "projectId=${task.projectId ?: "none"} | " +
                "userInputLength=${userInput.length}"
        }

        activeAgents[task.correlationId] = provider
        try {
            val agent: AIAgent<String, String> = create(task)

            logger.info {
                "🔧 KOOG_WORKFLOW_AGENT_CREATED | " +
                    "correlationId=${task.correlationId} | " +
                    "maxIterations=${koogProperties.maxIterations} | " +
                    "tools=[TaskMemory,RAG,Graph,Memory,Task,System,Communication,File,Shell] | " +
                    "subgraphs=[Research,Analysis,Execution,Storage]"
            }

            val output: String = agent.run(userInput)
            val duration = System.currentTimeMillis() - startTime

            logger.info {
                "✅ KOOG_WORKFLOW_SUCCESS | " +
                    "correlationId=${task.correlationId} | " +
                    "duration=${duration}ms | " +
                    "outputLength=${output.length}"
            }

            return output
        } catch (e: Exception) {
            val duration = System.currentTimeMillis() - startTime
            logger.error(e) {
                "❌ KOOG_WORKFLOW_FAILED | " +
                    "correlationId=${task.correlationId} | " +
                    "duration=${duration}ms | " +
                    "error=${e.message}"
            }
            throw e
        } finally {
            activeAgents.remove(task.correlationId)
        }
    }

    companion object {
        const val MODEL_WORKFLOW_NAME = "qwen3-coder-tool:30b"
    }
}
