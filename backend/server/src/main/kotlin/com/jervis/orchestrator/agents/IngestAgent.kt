package com.jervis.orchestrator.agents

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.dsl.builder.forwardTo
import ai.koog.agents.core.dsl.builder.strategy
import ai.koog.agents.core.dsl.extension.nodeExecuteTool
import ai.koog.agents.core.dsl.extension.nodeLLMRequest
import ai.koog.agents.core.dsl.extension.nodeLLMRequestStructured
import ai.koog.agents.core.dsl.extension.nodeLLMSendToolResult
import ai.koog.agents.core.dsl.extension.onAssistantMessage
import ai.koog.agents.core.dsl.extension.onToolCall
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.core.tools.reflect.tools
import ai.koog.prompt.dsl.Prompt
import com.jervis.entity.TaskDocument
import com.jervis.koog.KoogPromptExecutorFactory
import com.jervis.koog.SmartModelSelector
import com.jervis.koog.tools.KnowledgeStorageTools
import com.jervis.orchestrator.model.IngestResult
import com.jervis.rag.KnowledgeService
import com.jervis.rag.internal.graphdb.GraphDBService
import mu.KotlinLogging
import org.springframework.stereotype.Component

/**
 * IngestAgent - Internal agent responsible for indexing information into RAG and GraphDB.
 *
 * Role:
 * - Take provided information (facts, analysis results, etc.)
 * - Identify entities and relationships
 * - Use KnowledgeStorageTools to index everything properly
 *
 * Used by Orchestrator when:
 * - User provides specific data to be remembered
 * - Analysis produces insights that should be persistent
 * - New project facts are discovered during execution
 */
@Component
class IngestAgent(
    private val promptExecutorFactory: KoogPromptExecutorFactory,
    private val smartModelSelector: SmartModelSelector,
    private val knowledgeService: KnowledgeService,
    private val graphDBService: GraphDBService,
) {
    companion object {
        private val logger = KotlinLogging.logger {}
    }

    /**
     * Create ingest agent instance.
     */
    suspend fun create(task: TaskDocument): AIAgent<String, IngestResult> {
        val promptExecutor = promptExecutorFactory.getExecutor("OLLAMA")

        val agentStrategy =
            strategy<String, IngestResult>("Knowledge Ingestion") {
                val nodeLLMRequest by nodeLLMRequest()
                val nodeExecuteTool by nodeExecuteTool()
                val nodeSendToolResult by nodeLLMSendToolResult()

                // Structured result node
                val nodeRequestIngestResult by nodeLLMRequestStructured<IngestResult>(
                    name = "request-ingest-result",
                    examples =
                        listOf(
                            IngestResult(
                                success = true,
                                summary = "Successfully indexed information about UserService into RAG and GraphDB",
                                ingestedNodes = listOf("UserService"),
                            ),
                        ),
                ).transform { result ->
                    result
                        .getOrElse { e ->
                            throw IllegalStateException("IngestAgent: structured output parsing failed", e)
                        }.data
                }

                edge(nodeStart forwardTo nodeLLMRequest)

                // Standard tool-calling loop
                edge((nodeLLMRequest forwardTo nodeExecuteTool).onToolCall { true })
                edge((nodeLLMRequest forwardTo nodeRequestIngestResult).onAssistantMessage { true })

                edge(nodeExecuteTool forwardTo nodeSendToolResult)
                edge((nodeSendToolResult forwardTo nodeExecuteTool).onToolCall { true })
                edge((nodeSendToolResult forwardTo nodeRequestIngestResult).onAssistantMessage { true })

                edge(nodeRequestIngestResult forwardTo nodeFinish)
            }

        val model =
            smartModelSelector.selectModel(
                baseModelName = SmartModelSelector.BaseModelTypeEnum.AGENT,
                inputContent = task.content,
            )

        val agentConfig =
            AIAgentConfig(
                prompt =
                    Prompt.build("ingest-agent") {
                        system(
                            """
                            You are IngestAgent. Your goal is to index provided information into RAG and GraphDB.
                            
                            AVAILABLE TOOLS:
                            - storeKnowledgeWithGraph: Store text content in RAG and create graph structure.
                            
                            YOUR WORKFLOW:
                            1. Analyze the input 'INFORMATION_TO_INGEST'.
                            2. Decompose it into logical entities (mainNodeKey) and relationships (from|edge|to).
                            3. For each significant piece of information, call storeKnowledgeWithGraph().
                            4. When finished, provide a structured IngestResult.
                            
                            CRITICAL RULES:
                            - Be precise and structured.
                            - Choose descriptive mainNodeKey names (e.g. 'class:UserService', 'feature:auth').
                            - Relationships should follow 'Source|Relation|Target' format.
                            - If the information is too vague, try to infer context or mark it in the summary.
                            - Do NOT invent information not present in the input.
                            """.trimIndent(),
                        )
                    },
                model = model,
                maxAgentIterations = 5,
            )

        val toolRegistry =
            ToolRegistry {
                tools(KnowledgeStorageTools(task, knowledgeService, graphDBService))
            }

        return AIAgent(
            promptExecutor = promptExecutor,
            toolRegistry = toolRegistry,
            strategy = agentStrategy,
            agentConfig = agentConfig,
        )
    }

    /**
     * Run ingest agent.
     */
    suspend fun run(
        task: TaskDocument,
        informationToIngest: String,
    ): IngestResult {
        logger.info { "INGEST_AGENT_START | correlationId=${task.correlationId}" }

        val agent = create(task)
        val result = agent.run("INFORMATION_TO_INGEST:\n$informationToIngest")

        logger.debug { "INGEST_AGENT_COMPLETE | success=${result.success} summary='${result.summary}'" }

        return result
    }
}
