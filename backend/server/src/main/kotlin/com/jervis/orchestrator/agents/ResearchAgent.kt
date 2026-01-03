package com.jervis.orchestrator.agents

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.dsl.builder.forwardTo
import ai.koog.agents.core.dsl.builder.strategy
import ai.koog.agents.core.dsl.extension.*
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.core.tools.reflect.tools
import ai.koog.prompt.dsl.Prompt
import com.jervis.entity.TaskDocument
import com.jervis.koog.KoogPromptExecutorFactory
import com.jervis.koog.SmartModelSelector
import com.jervis.koog.tools.KnowledgeStorageTools
import com.jervis.orchestrator.model.ContextPack
import com.jervis.orchestrator.model.EvidencePack
import com.jervis.rag.KnowledgeService
import com.jervis.rag.internal.graphdb.GraphDBService
import mu.KotlinLogging
import org.springframework.stereotype.Component

/**
 * ResearchAgent - Internal agent for evidence gathering.
 *
 * Role:
 * - Call multiple tools (RAG, GraphDB, Joern, logs) to gather evidence
 * - Iterate until has enough information or reaches max iterations
 * - Return EvidencePack with collected items and summary
 *
 * Koog pattern: Tool-call loop with stopping condition.
 *
 * Used by Orchestrator when:
 * - Planner creates "research" step
 * - Context shows missingInfo
 * - Need evidence before making decisions
 */
@Component
class ResearchAgent(
    private val promptExecutorFactory: KoogPromptExecutorFactory,
    private val smartModelSelector: SmartModelSelector,
    private val knowledgeService: KnowledgeService,
    private val graphDBService: GraphDBService,
) {
    companion object {
        private val logger = KotlinLogging.logger {}
    }

    /**
     * Create research agent instance.
     * Returns EvidencePack directly - Koog serializes automatically.
     */
    suspend fun create(
        task: TaskDocument,
        context: ContextPack,
    ): AIAgent<String, EvidencePack> {
        val promptExecutor = promptExecutorFactory.getExecutor("OLLAMA")

        val agentStrategy =
            strategy("Research Loop") {
                // Koog pattern: LLM request → tool execution → send results → repeat or finish
                val nodeLLMRequest by nodeLLMRequest()
                val nodeExecuteTool by nodeExecuteTool()
                val nodeSendToolResult by nodeLLMSendToolResult()
                val nodeCompressHistory by nodeLLMCompressHistory()

                // Final node that builds EvidencePack from conversation
                val nodeBuildEvidence by node<String, EvidencePack> { llmResponse ->
                    parseEvidenceFromText(llmResponse, "research_completed")
                }

                edge(nodeStart forwardTo nodeLLMRequest)

                // On assistant message (no tool call) → build evidence pack
                edge((nodeLLMRequest forwardTo nodeBuildEvidence).onAssistantMessage { true })
                edge(nodeBuildEvidence forwardTo nodeFinish)

                // On tool call → execute tool
                edge((nodeLLMRequest forwardTo nodeExecuteTool).onToolCall { true })

                // Compress history if too large (avoid token overflow)
                edge(
                    (nodeExecuteTool forwardTo nodeCompressHistory)
                        .onCondition { llm.readSession { prompt.messages.size > 30 } },
                )
                edge(nodeCompressHistory forwardTo nodeSendToolResult)

                edge(
                    (nodeExecuteTool forwardTo nodeSendToolResult)
                        .onCondition { llm.readSession { prompt.messages.size <= 30 } },
                )

                // After sending tool result:
                // - If assistant says "done" → build evidence pack
                // - If assistant calls more tools → loop back
                edge((nodeSendToolResult forwardTo nodeBuildEvidence).onAssistantMessage { true })
                edge((nodeSendToolResult forwardTo nodeExecuteTool).onToolCall { true })
            }

        val model =
            smartModelSelector.selectModel(
                baseModelName = SmartModelSelector.BaseModelTypeEnum.AGENT,
                inputContent = task.content,
            )

        val agentConfig =
            AIAgentConfig(
                prompt =
                    Prompt.build("research-agent") {
                        system(
                            """
                            You are Research Agent. Gather evidence by calling tools.

                            Available tools:
                            - searchKnowledge: Search RAG for relevant documents/code
                            - queryGraph: Query GraphDB for entities and relationships
                            - searchInFiles: Search codebase files (if needed)

                            Your goal: Collect enough evidence to answer the research question.

                            When to stop:
                            1. You have gathered sufficient information
                            2. Multiple tools return similar/redundant info
                            3. Reached max iterations (you'll be stopped automatically)

                            When done, output a clear summary:
                            - List all evidence items found
                            - Note the source of each (RAG, GraphDB, files)
                            - Provide high-level conclusion

                            Be thorough but efficient. Don't repeat the same query.
                            """.trimIndent(),
                        )
                    },
                model = model,
                maxAgentIterations = 10, // Max research iterations
            )

        val toolRegistry =
            ToolRegistry {
                // Knowledge tools (RAG + GraphDB)
                tools(KnowledgeStorageTools(task, knowledgeService, graphDBService))

                // TODO: Add Joern tools, log search tools when available
            }

        return AIAgent(
            promptExecutor = promptExecutor,
            toolRegistry = toolRegistry,
            strategy = agentStrategy,
            agentConfig = agentConfig,
        )
    }

    /**
     * Run research agent.
     *
     * @param task TaskDocument
     * @param researchQuestion What to research
     * @param context Context from ContextAgent
     * @return EvidencePack with gathered evidence
     */
    suspend fun run(
        task: TaskDocument,
        researchQuestion: String,
        context: ContextPack,
    ): EvidencePack {
        logger.info { "RESEARCH_AGENT_START | correlationId=${task.correlationId} | question=$researchQuestion" }

        val agent = create(task, context)

        val promptInput =
            buildString {
                appendLine("RESEARCH_QUESTION: $researchQuestion")
                appendLine()
                appendLine("CONTEXT:")
                if (context.projectName != null) {
                    appendLine("  project: ${context.projectName}")
                }
                appendLine("  environment: ${context.environmentHints}")
                if (context.knownFacts.isNotEmpty()) {
                    appendLine("  knownFacts: ${context.knownFacts.take(3).joinToString("; ")}")
                }
                if (context.missingInfo.isNotEmpty()) {
                    appendLine("  missingInfo: ${context.missingInfo.joinToString("; ")}")
                }
                appendLine()
                appendLine("Gather evidence using available tools.")
            }

        val result = agent.run(promptInput)

        logger.debug { "RESEARCH_AGENT_COMPLETE | items=${result.items.size}" }

        return result
    }

    /**
     * Parse agent's text output into EvidencePack.
     * Agent gathered evidence via tools, we structure the results.
     */
    private fun parseEvidenceFromText(
        text: String,
        researchQuestion: String,
    ): EvidencePack {
        // Extract evidence items from text
        // Simple heuristic: each paragraph or bullet point = evidence item
        val items = mutableListOf<com.jervis.orchestrator.model.EvidenceItem>()

        // Split by double newlines (paragraphs) or bullets
        val sections =
            text.split(Regex("\n\n+|\\n\\s*[-*]\\s+"))
                .filter { it.trim().length > 20 } // Skip short lines

        for (section in sections.take(10)) {
            items.add(
                com.jervis.orchestrator.model.EvidenceItem(
                    source = detectSource(section),
                    content = section.trim(),
                    confidence = 0.7, // Default confidence
                ),
            )
        }

        return EvidencePack(
            items = items,
            summary = text.take(500), // Use first 500 chars as summary
        )
    }

    /**
     * Detect evidence source from text content.
     */
    private fun detectSource(text: String): String {
        val lower = text.lowercase()
        return when {
            lower.contains("rag") || lower.contains("document") -> "RAG"
            lower.contains("graph") || lower.contains("node") -> "GraphDB"
            lower.contains("file") || lower.contains("code") -> "Files"
            lower.contains("log") -> "Logs"
            else -> "research_agent"
        }
    }
}
