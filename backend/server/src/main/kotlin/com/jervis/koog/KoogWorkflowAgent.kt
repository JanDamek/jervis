package com.jervis.koog

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.dsl.builder.forwardTo
import ai.koog.agents.core.dsl.builder.strategy
import ai.koog.agents.core.dsl.extension.*
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.core.tools.reflect.tools
import ai.koog.prompt.dsl.Prompt
import ai.koog.prompt.executor.llms.all.simpleOllamaAIExecutor
import ai.koog.prompt.llm.OllamaModels
import com.jervis.domain.plan.Plan
import com.jervis.graphdb.GraphDBService
import com.jervis.graphdb.model.Direction
import com.jervis.koog.mcp.KoogBridgeTools
import com.jervis.mcp.McpToolRegistry
import com.jervis.mcp.tools.KnowledgeSearchTool
import com.jervis.mcp.McpTool
import kotlinx.coroutines.runBlocking
import org.springframework.stereotype.Service

/**
 * KoogWorkflowAgent – official Koog AIAgent wiring for JERVIS complex workflows.
 * Builds a minimal, production-ready strategy graph and exposes JERVIS MCP tools via a Koog ToolRegistry.
 */
@Service
class KoogWorkflowAgent {
    fun create(
        plan: Plan,
        mcpRegistry: McpToolRegistry,
        graph: GraphDBService,
    ): AIAgent<String, String> {
        val promptExecutor = simpleOllamaAIExecutor()

        val agentStrategy =
            strategy("JERVIS complex workflow") {
                // Pre-enrichment: add RAG/Graph context before the conversation starts
                val nodePreEnrich by node<String, String> { input ->
                    val rag: String =
                        runBlocking {
                            try {
                                val tool =
                                    mcpRegistry.byName(
                                        com.jervis.configuration.prompts.ToolTypeEnum.KNOWLEDGE_SEARCH_TOOL,
                                    ) as McpTool<KnowledgeSearchTool.Description>
                                val query =
                                    plan.initialRagQueries.firstOrNull()?.take(500)
                                        ?: input.take(500)
                                val req =
                                    KnowledgeSearchTool.Description(
                                        query = query,
                                        maxResult = 15,
                                        minScore = 0.55,
                                        knowledgeTypes = null,
                                    )
                                val result = tool.execute(plan, req)
                                when (result) {
                                    is com.jervis.mcp.domain.ToolResult.Ok -> result.output
                                    is com.jervis.mcp.domain.ToolResult.Error -> ""
                                    else -> ""
                                }
                            } catch (_: Exception) {
                                ""
                            }
                        }

                    val graphSummary: String =
                        runBlocking {
                            try {
                                val clientKey = "client::${plan.clientDocument.id.toHexString()}"
                                val projectKey =
                                    plan.projectDocument
                                        ?.id
                                        ?.toHexString()
                                        ?.let { "project::$it" }
                                val key = projectKey ?: clientKey
                                val nodes =
                                    graph.getRelated(
                                        clientId = plan.clientDocument.id.toHexString(),
                                        nodeKey = key,
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
                                            val title = (n.props["title"] ?: n.props["name"])?.toString()
                                            append("- ")
                                            append(n.key)
                                            title?.let {
                                                append(" :: ")
                                                append(it)
                                            }
                                            appendLine()
                                        }
                                    }
                                }
                            } catch (_: Exception) {
                                ""
                            }
                        }

                    val targetLang = plan.originalLanguage.ifBlank { "EN" }

                    buildString {
                        appendLine("TARGET_LANGUAGE: ")
                        appendLine(targetLang)
                        if (rag.isNotBlank()) {
                            appendLine()
                            appendLine("[RAG]")
                            appendLine(rag)
                        }
                        if (graphSummary.isNotBlank()) {
                            appendLine()
                            appendLine("[GRAPH]")
                            appendLine(graphSummary)
                        }
                        appendLine()
                        appendLine("[USER_INPUT]")
                        appendLine(input)
                    }.trim()
                }

                val nodeSendInput by nodeLLMRequest()
                val nodeExecuteTool by nodeExecuteTool()
                val nodeSendToolResult by nodeLLMSendToolResult()

                // Start -> Send input
                edge(nodeStart forwardTo nodePreEnrich)
                edge(nodePreEnrich forwardTo nodeSendInput)

                // Send input -> Finish on assistant message
                edge(
                    (nodeSendInput forwardTo nodeFinish)
                        .transformed { it }
                        .onAssistantMessage { true },
                )

                // Send input -> Execute tool on tool call
                edge(
                    (nodeSendInput forwardTo nodeExecuteTool)
                        .onToolCall { true },
                )

                // Execute tool -> Send the tool result
                edge(nodeExecuteTool forwardTo nodeSendToolResult)

                // Send the tool result -> Finish (assistant message will follow language guidance from context)
                edge(
                    (nodeSendToolResult forwardTo nodeFinish)
                        .transformed { it }
                        .onAssistantMessage { true },
                )
            }

        val agentConfig =
            AIAgentConfig(
                prompt =
                    Prompt.build("jervis-agent") {
                        system(
                            """
                            You are JERVIS Agent built with Koog framework.
                            Solve user's objectives by calling tools precisely.
                            Prefer concise, actionable outputs.
                            Always respond in the user's input language. If TARGET_LANGUAGE is provided in the context, use it for the final answer.
                            """.trimIndent(),
                        )
                    },
                model = OllamaModels.Meta.LLAMA_3_2,
                maxAgentIterations = 8,
            )

        val toolRegistry =
            ToolRegistry {
                tools(KoogBridgeTools(plan, mcpRegistry))
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
        mcpRegistry: McpToolRegistry,
        graph: GraphDBService,
        userInput: String,
    ): String {
        val agent: AIAgent<String, String> = create(plan, mcpRegistry, graph)
        val result: String = agent.run(userInput)
        return result
    }
}
