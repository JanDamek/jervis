package com.jervis.koog

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.dsl.builder.forwardTo
import ai.koog.agents.core.dsl.builder.strategy
import ai.koog.agents.core.dsl.extension.*
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.core.tools.reflect.tools
import ai.koog.prompt.dsl.Prompt
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.llm.LLMProvider
import com.jervis.domain.plan.Plan
import com.jervis.graphdb.GraphDBService
import com.jervis.graphdb.model.Direction
import ai.koog.agents.ext.tool.file.*
import ai.koog.agents.ext.tool.shell.ExecuteShellCommandTool
import ai.koog.agents.ext.tool.shell.JvmShellCommandExecutor
import ai.koog.agents.ext.tool.shell.PrintShellCommandConfirmationHandler
import ai.koog.rag.base.files.JVMFileSystemProvider
import com.jervis.koog.tools.*
import com.jervis.rag.EmbeddingType
import com.jervis.rag.KnowledgeService
import com.jervis.rag.SearchRequest
import com.jervis.service.agent.AgentMemoryService
import com.jervis.service.background.PendingTaskService
import com.jervis.service.dialog.UserDialogCoordinator
import com.jervis.service.link.LinkIndexingService
import com.jervis.service.scheduling.TaskManagementService
import com.jervis.service.task.UserTaskService
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import org.springframework.stereotype.Service

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
    private val memoryService: AgentMemoryService,
    private val knowledgeService: KnowledgeService,
    private val taskManagementService: TaskManagementService,
    private val userTaskService: UserTaskService,
    private val userDialogCoordinator: UserDialogCoordinator,
    private val linkIndexingService: LinkIndexingService,
    private val pendingTaskService: PendingTaskService,
    private val promptExecutorFactory: KoogPromptExecutorFactory,
    private val taskMemoryService: com.jervis.service.agent.TaskMemoryService,
) {
    private val logger = KotlinLogging.logger {}
    data class EnrichmentContext(
        val ragContext: String,
        val graphContext: String,
        val targetLanguage: String,
    )

    fun create(
        plan: Plan,
        graph: GraphDBService,
        providerName: String = "OLLAMA",
        modelName: String = "qwen3:30b",
    ): AIAgent<String, String> {
        val promptExecutor = promptExecutorFactory.createExecutor(providerName)

        val agentStrategy =
            strategy("JERVIS modular workflow") {
                // =================================================================
                // PARALLEL ENRICHMENT - RAG + Graph simultaneously
                // =================================================================
                val nodeParallelEnrich by node<String, EnrichmentContext> { input ->
                    val ragResult = runBlocking {
                        try {
                            val query = plan.initialRagQueries.firstOrNull()?.take(500) ?: input.take(500)
                            val searchRequest = SearchRequest(
                                query = query,
                                clientId = plan.clientDocument.id,
                                projectId = plan.projectDocument?.id,
                                maxResults = 15,
                                minScore = 0.55,
                                embeddingType = EmbeddingType.TEXT,
                                knowledgeTypes = null,
                            )
                            knowledgeService.search(searchRequest).text
                        } catch (_: Exception) {
                            ""
                        }
                    }

                    val graphResult = runBlocking {
                        try {
                            val clientKey = "client::${plan.clientDocument.id.toHexString()}"
                            val projectKey = plan.projectDocument?.id?.toHexString()?.let { "project::$it" }
                            val key = projectKey ?: clientKey
                            val nodes = graph.getRelated(
                                clientId = plan.clientDocument.id.toHexString(),
                                nodeKey = key,
                                edgeTypes = emptyList(),
                                direction = Direction.ANY,
                                limit = 20,
                            )
                            if (nodes.isEmpty()) "" else buildString {
                                appendLine("Graph related nodes (summary):")
                                nodes.take(20).forEach { n ->
                                    val title = (n.props["title"] ?: n.props["name"])?.toString()
                                    append("- ").append(n.key)
                                    title?.let { append(" :: ").append(it) }
                                    appendLine()
                                }
                            }
                        } catch (_: Exception) {
                            ""
                        }
                    }

                    EnrichmentContext(
                        ragContext = ragResult,
                        graphContext = graphResult,
                        targetLanguage = plan.originalLanguage.ifBlank { "EN" },
                    )
                }

                val nodePrepareInput by node<EnrichmentContext, String> { ctx ->
                    buildString {
                        appendLine("TARGET_LANGUAGE: ${ctx.targetLanguage}")
                        if (ctx.ragContext.isNotBlank()) {
                            appendLine("\n[RAG]\n${ctx.ragContext}")
                        }
                        if (ctx.graphContext.isNotBlank()) {
                            appendLine("\n[GRAPH]\n${ctx.graphContext}")
                        }
                        appendLine("\n[USER_INPUT]\n${llm.readSession { prompt.messages.firstOrNull()?.content ?: "" }}")
                    }.trim()
                }

                // =================================================================
                // RESEARCH SUBGRAPH - Information gathering
                // Tools: RAG search, Graph query, Memory read, File read, Directory list
                // =================================================================
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
                    edge((nodeExecuteTool forwardTo nodeCompressHistory)
                        .onCondition { llm.readSession { prompt.messages.size > 50 } })
                    edge(nodeCompressHistory forwardTo nodeSendToolResult)

                    edge((nodeExecuteTool forwardTo nodeSendToolResult)
                        .onCondition { llm.readSession { prompt.messages.size <= 50 } })

                    edge((nodeSendToolResult forwardTo nodeFinish).onAssistantMessage { true })
                    edge((nodeSendToolResult forwardTo nodeExecuteTool).onToolCall { true })
                }

                // =================================================================
                // ANALYSIS SUBGRAPH - Decision making
                // Tools: RAG search, Graph operations, Memory operations
                // =================================================================
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

                    edge((nodeExecuteTool forwardTo nodeCompressHistory)
                        .onCondition { llm.readSession { prompt.messages.size > 50 } })
                    edge(nodeCompressHistory forwardTo nodeSendToolResult)

                    edge((nodeExecuteTool forwardTo nodeSendToolResult)
                        .onCondition { llm.readSession { prompt.messages.size <= 50 } })

                    edge((nodeSendToolResult forwardTo nodeFinish).onAssistantMessage { true })
                    edge((nodeSendToolResult forwardTo nodeExecuteTool).onToolCall { true })
                }

                // =================================================================
                // EXECUTION SUBGRAPH - Action execution
                // Tools: File write/edit, Shell commands, Task management, Communication
                // =================================================================
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
                    edge((nodeExecuteTool forwardTo nodeCompressHistory)
                        .onCondition { llm.readSession { prompt.messages.size > 30 } })
                    edge(nodeCompressHistory forwardTo nodeSendToolResult)

                    edge((nodeExecuteTool forwardTo nodeSendToolResult)
                        .onCondition { llm.readSession { prompt.messages.size <= 30 } })

                    edge((nodeSendToolResult forwardTo nodeFinish).onAssistantMessage { true })
                    edge((nodeSendToolResult forwardTo nodeExecuteTool).onToolCall { true })
                }

                // =================================================================
                // STORAGE SUBGRAPH - Persist results
                // Tools: RAG store, Graph upsert, Memory write
                // =================================================================
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

                // =================================================================
                // MAIN FLOW - Enrichment -> Subgraphs -> Finish
                // =================================================================
                edge(nodeStart forwardTo nodeParallelEnrich)
                edge(nodeParallelEnrich forwardTo nodePrepareInput)

                // Route to appropriate subgraph based on input
                // Default: go through all subgraphs in sequence for complex tasks
                edge((nodePrepareInput forwardTo researchSubgraph).transformed { it })
                edge((researchSubgraph forwardTo analysisSubgraph).transformed { it })
                edge((analysisSubgraph forwardTo executionSubgraph).transformed { it })
                edge((executionSubgraph forwardTo storageSubgraph).transformed { it })
                edge((storageSubgraph forwardTo nodeFinish).transformed { it })
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

                            GUIDELINES:
                            - Store important findings as MEMORY for future reference
                            - Create user tasks for items requiring human action
                            - Always respond in TARGET_LANGUAGE from context
                            - Be concise and actionable
                            """.trimIndent(),
                        )
                    },
                model = LLModel(LLMProvider.Ollama, modelName, emptyList(), 128000),
                maxAgentIterations = 20,
            )

        val toolRegistry =
            ToolRegistry {
                // File operations (read-only + write)
                tools(
                    listOf(
                        ListDirectoryTool(JVMFileSystemProvider.ReadOnly),
                        ReadFileTool(JVMFileSystemProvider.ReadOnly),
                        EditFileTool(JVMFileSystemProvider.ReadWrite),
                        WriteFileTool(JVMFileSystemProvider.ReadWrite),
                    )
                )

                // Shell execution
                tools(
                    listOf(
                        ExecuteShellCommandTool(
                            JvmShellCommandExecutor(),
                            PrintShellCommandConfirmationHandler(),
                        )
                    )
                )

                // JERVIS native tools (ToolSets)
                tools(TaskMemoryTool(plan, taskMemoryService)) // NEW: Load context from Qualifier
                tools(RagTools(plan, knowledgeService))
                tools(GraphTools(plan, graph))
                tools(MemoryTools(plan, memoryService))
                tools(TaskTools(plan, taskManagementService, userTaskService, userDialogCoordinator))
                tools(SystemTools(plan, linkIndexingService, pendingTaskService))
                tools(CommunicationTools(plan))
            }

        return AIAgent(
            promptExecutor = promptExecutor,
            toolRegistry = toolRegistry,
            strategy = agentStrategy,
            agentConfig = agentConfig,
        )
    }

    suspend fun run(
        plan: Plan,
        graph: GraphDBService,
        userInput: String,
        providerName: String = "OLLAMA",
        modelName: String = "qwen3:30b",
    ): String {
        val startTime = System.currentTimeMillis()

        logger.info {
            "🟢 KOOG_WORKFLOW_START | " +
            "correlationId=${plan.correlationId} | " +
            "model=$modelName | " +
            "provider=$providerName | " +
            "clientId=${plan.clientDocument.id.toHexString()} | " +
            "projectId=${plan.projectDocument?.id?.toHexString() ?: "none"} | " +
            "userInputLength=${userInput.length}"
        }

        try {
            val agent: AIAgent<String, String> = create(plan, graph, providerName, modelName)

            logger.info {
                "🔧 KOOG_WORKFLOW_AGENT_CREATED | " +
                "correlationId=${plan.correlationId} | " +
                "maxIterations=20 | " +
                "tools=[TaskMemory,RAG,Graph,Memory,Task,System,Communication,File,Shell] | " +
                "subgraphs=[Research,Analysis,Execution,Storage]"
            }

            val output: String = agent.run(userInput)
            val duration = System.currentTimeMillis() - startTime

            logger.info {
                "✅ KOOG_WORKFLOW_SUCCESS | " +
                "correlationId=${plan.correlationId} | " +
                "duration=${duration}ms | " +
                "outputLength=${output.length}"
            }

            return output
        } catch (e: Exception) {
            val duration = System.currentTimeMillis() - startTime
            logger.error(e) {
                "❌ KOOG_WORKFLOW_FAILED | " +
                "correlationId=${plan.correlationId} | " +
                "duration=${duration}ms | " +
                "error=${e.message}"
            }
            throw e
        }
    }
}
