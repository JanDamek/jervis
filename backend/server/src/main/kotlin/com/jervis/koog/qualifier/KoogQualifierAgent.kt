package com.jervis.koog.qualifier

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
import com.jervis.domain.llm.LlmResponse
import com.jervis.domain.plan.Plan
import com.jervis.graphdb.GraphDBService
import com.jervis.koog.KoogPromptExecutorFactory
import ai.koog.agents.ext.tool.file.ListDirectoryTool
import ai.koog.agents.ext.tool.file.ReadFileTool
import ai.koog.rag.base.files.JVMFileSystemProvider
import com.jervis.koog.tools.GraphRagLinkerTool
import com.jervis.koog.tools.GraphTools
import com.jervis.koog.tools.MemoryTools
import com.jervis.koog.tools.RagTools
import com.jervis.koog.tools.SequentialIndexingTool
import com.jervis.koog.tools.TaskRoutingTool
import com.jervis.service.agent.AgentMemoryService
import mu.KotlinLogging
import org.springframework.stereotype.Service

/**
 * KoogQualifierAgent – Koog-based qualifier agent for DATA_PROCESSING tasks.
 *
 * NEW ARCHITECTURE (Graph-Based Routing):
 * - CPU-based qualifier for structuring newly discovered documents
 * - Uses OLLAMA_QUALIFIER provider (CPU endpoint) for cost-efficient processing
 * - Handles chunking for large documents (4000 char blocks with 200 char overlap)
 * - Creates Graph nodes with metadata + RAG chunks for semantic search
 * - Routes to GPU (KoogWorkflowAgent) only if complex analysis needed
 *
 * Tools:
 * - SequentialIndexingTool: Index documents into RAG with automatic chunking
 * - GraphRagLinkerTool: Create Graph nodes with bi-directional RAG links
 * - RagTools: Knowledge search (for context)
 * - GraphTools: Entity relationships and graph traversal
 * - MemoryTools: Long-term persistent memory (shared with KoogWorkflowAgent)
 * - File Tools: Read-only file system access
 *
 * Strategy:
 * - Start -> SendInput -> ExecuteTool -> SendToolResult -> Finish
 * - Max 10 iterations for large documents (chunking loop)
 * - Automatically handles single-pass (small) vs multi-pass (large) processing
 *
 * Task Flow:
 * 1. Receive DATA_PROCESSING task (email, Jira, Git commit, etc.)
 * 2. Check content size
 * 3. If small: single-pass indexing (SequentialIndexingTool)
 * 4. If large: multi-pass with overlap (chunking loop)
 * 5. Create Graph node with RAG links (GraphRagLinkerTool)
 * 6. Decide routing: DONE (simple) or READY_FOR_GPU (complex analysis needed)
 */
@Service
class KoogQualifierAgent(
    private val promptExecutorFactory: KoogPromptExecutorFactory,
    private val graphService: GraphDBService,
    private val memoryService: AgentMemoryService,
    private val knowledgeService: com.jervis.rag.KnowledgeService,
) {
    private val logger = KotlinLogging.logger {}

    fun create(
        plan: Plan,
        systemPrompt: String,
        modelName: String,
    ): AIAgent<String, String> {
        // Create PromptExecutor using OLLAMA_QUALIFIER provider
        val promptExecutor = promptExecutorFactory.createExecutor("OLLAMA_QUALIFIER")

        logger.info { "KOOG_QUALIFIER: Creating agent with model=$modelName, provider=OLLAMA_QUALIFIER" }

        val agentStrategy =
            strategy("JERVIS qualifier workflow") {
                val nodeSendInput by nodeLLMRequest()
                val nodeExecuteTool by nodeExecuteTool()
                val nodeSendToolResult by nodeLLMSendToolResult()

                // Start -> SendInput
                edge(nodeStart forwardTo nodeSendInput)

                // SendInput -> Finish (if assistant responds)
                edge((nodeSendInput forwardTo nodeFinish).transformed { it }.onAssistantMessage { true })

                // SendInput -> ExecuteTool (if tool call)
                edge((nodeSendInput forwardTo nodeExecuteTool).onToolCall { true })

                // ExecuteTool -> SendToolResult
                edge(nodeExecuteTool forwardTo nodeSendToolResult)

                // SendToolResult -> Finish (after tool execution)
                edge((nodeSendToolResult forwardTo nodeFinish).transformed { it }.onAssistantMessage { true })

                // SendToolResult -> ExecuteTool (for chaining tool calls)
                edge((nodeSendToolResult forwardTo nodeExecuteTool).onToolCall { true })
            }

        val agentConfig =
            AIAgentConfig(
                prompt =
                    Prompt.build("jervis-qualifier") {
                        system(systemPrompt)
                    },
                model = LLModel(LLMProvider.Ollama, modelName, emptyList(), 128000),
                maxAgentIterations = 10, // Max 10 iterations for large document chunking loops
            )

        val toolRegistry =
            ToolRegistry {
                // NEW: Sequential Indexing Tool (with chunking for large documents)
                tools(
                    SequentialIndexingTool(
                        plan = plan,
                        knowledgeService = knowledgeService,
                    )
                )

                // NEW: Graph-RAG Linker Tool (bi-directional linking)
                tools(
                    GraphRagLinkerTool(
                        plan = plan,
                        graphService = graphService,
                    )
                )

                // NEW: Task Routing Tool (DONE vs READY_FOR_GPU decision)
                tools(
                    TaskRoutingTool(
                        plan = plan,
                    )
                )

                // Koog File System Tools (read-only for qualifier)
                tools(
                    listOf(
                        ListDirectoryTool(JVMFileSystemProvider.ReadOnly),
                        ReadFileTool(JVMFileSystemProvider.ReadOnly),
                    )
                )

                // JERVIS RAG Tools (search for context)
                tools(
                    RagTools(
                        plan = plan,
                        knowledgeService = knowledgeService,
                    )
                )

                // JERVIS Graph Tools (entity relationships)
                tools(
                    GraphTools(
                        plan = plan,
                        graphService = graphService,
                    )
                )

                // JERVIS Memory Tools (shared with KoogWorkflowAgent)
                tools(
                    MemoryTools(
                        plan = plan,
                        memoryService = memoryService,
                    )
                )
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
        systemPrompt: String,
        userPrompt: String,
        modelName: String,
    ): LlmResponse {
        val startTime = System.currentTimeMillis()

        logger.info {
            "🔵 KOOG_QUALIFIER_START | " +
            "correlationId=${plan.correlationId} | " +
            "model=$modelName | " +
            "provider=OLLAMA_QUALIFIER | " +
            "clientId=${plan.clientDocument.id.toHexString()} | " +
            "projectId=${plan.projectDocument?.id?.toHexString() ?: "none"} | " +
            "userPromptLength=${userPrompt.length} | " +
            "systemPromptLength=${systemPrompt.length}"
        }

        try {
            val agent = create(plan, systemPrompt, modelName)

            logger.info {
                "🔧 KOOG_QUALIFIER_AGENT_CREATED | " +
                "correlationId=${plan.correlationId} | " +
                "maxIterations=10 | " +
                "tools=[SequentialIndexing,GraphRagLinker,TaskRouting,RAG,Graph,Memory,File]"
            }

            val output: String = agent.run(userPrompt)
            val duration = System.currentTimeMillis() - startTime

            // Check for routing decision in plan metadata
            val routingDecision = plan.metadata["routing_decision"] as? String
            val routingReason = plan.metadata["routing_reason"] as? String

            logger.info {
                "✅ KOOG_QUALIFIER_SUCCESS | " +
                "correlationId=${plan.correlationId} | " +
                "duration=${duration}ms | " +
                "outputLength=${output.length} | " +
                "routing=${routingDecision ?: "not_set"} | " +
                "reason=${routingReason ?: "n/a"}"
            }

            return LlmResponse(
                answer = output,
                model = modelName,
                promptTokens = 0,
                completionTokens = 0,
                totalTokens = 0,
                finishReason = "stop",
            )
        } catch (e: Exception) {
            val duration = System.currentTimeMillis() - startTime
            logger.error(e) {
                "❌ KOOG_QUALIFIER_FAILED | " +
                "correlationId=${plan.correlationId} | " +
                "duration=${duration}ms | " +
                "error=${e.message}"
            }
            throw e
        }
    }
}
