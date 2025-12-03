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
import com.jervis.koog.tools.GraphTools
import com.jervis.koog.tools.MemoryTools
import com.jervis.koog.tools.RagTools
import com.jervis.service.agent.AgentMemoryService
import mu.KotlinLogging
import org.springframework.stereotype.Service

/**
 * KoogQualifierAgent – Koog-based qualifier agent for complex workflow qualification.
 *
 * Features:
 * - Uses OLLAMA_QUALIFIER provider (CPU endpoint) with Koog PromptExecutor
 * - Max 4 iterations for complex decision making
 * - RAG Tools: knowledge search and indexing
 * - Graph Tools: entity relationships and graph traversal
 * - Memory Tools: long-term persistent memory with audit trail (shared with KoogWorkflowAgent)
 *
 * Strategy: Start -> SendInput -> ExecuteTool -> SendToolResult -> Finish (max 4 iterations)
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
                maxAgentIterations = 4, // Max 4 iterations for complex qualification
            )

        val toolRegistry =
            ToolRegistry {
                // Koog File System Tools (read-only for qualifier)
                tools(
                    listOf(
                        ListDirectoryTool(JVMFileSystemProvider.ReadOnly),
                        ReadFileTool(JVMFileSystemProvider.ReadOnly),
                    )
                )

                // JERVIS RAG Tools
                tools(
                    RagTools(
                        plan = plan,
                        knowledgeService = knowledgeService,
                    )
                )

                // JERVIS Graph Tools
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
        logger.info { "KOOG_QUALIFIER: start model=$modelName, provider=OLLAMA_QUALIFIER" }
        val agent = create(plan, systemPrompt, modelName)
        val output: String = agent.run(userPrompt)
        logger.info { "KOOG_QUALIFIER: finish chars=${output.length}" }

        return LlmResponse(
            answer = output,
            model = modelName,
            promptTokens = 0,
            completionTokens = 0,
            totalTokens = 0,
            finishReason = "stop",
        )
    }
}
