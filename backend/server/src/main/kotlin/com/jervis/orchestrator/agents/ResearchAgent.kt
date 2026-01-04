package com.jervis.orchestrator.agents

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.dsl.builder.forwardTo
import ai.koog.agents.core.dsl.builder.strategy
import ai.koog.agents.core.dsl.extension.nodeExecuteTool
import ai.koog.agents.core.dsl.extension.nodeLLMCompressHistory
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
import com.jervis.koog.tools.external.ConfluenceReadTools
import com.jervis.koog.tools.external.EmailReadTools
import com.jervis.koog.tools.external.JiraReadTools
import com.jervis.orchestrator.model.ContextPack
import com.jervis.orchestrator.model.EvidencePack
import com.jervis.rag.KnowledgeService
import com.jervis.rag.internal.graphdb.GraphDBService
import com.jervis.service.confluence.ConfluenceService
import com.jervis.service.email.EmailService
import com.jervis.service.jira.JiraService
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
    private val jiraService: JiraService,
    private val confluenceService: ConfluenceService,
    private val emailService: EmailService,
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

                // Final node that builds EvidencePack from conversation using structured output
                val nodeRequestEvidencePack by
                    nodeLLMRequestStructured<EvidencePack>(
                        name = "request-evidence-pack",
                        examples =
                            listOf(
                                EvidencePack(
                                    items =
                                        listOf(
                                            com.jervis.orchestrator.model.EvidenceItem(
                                                source = "RAG",
                                                content = "Found implementation of UserService in src/main/UserService.kt",
                                                confidence = 0.9,
                                            ),
                                        ),
                                    summary = "Located UserService implementation",
                                ),
                            ),
                    ).transform { result ->
                        result
                            .getOrElse { e ->
                                throw IllegalStateException("ResearchAgent: structured output parsing failed", e)
                            }.data
                    }

                edge(nodeStart forwardTo nodeLLMRequest)

                // On assistant message (no tool call) → request structured evidence pack
                edge((nodeLLMRequest forwardTo nodeRequestEvidencePack).onAssistantMessage { true })
                edge(nodeRequestEvidencePack forwardTo nodeFinish)

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
                // - If assistant says "done" → request structured evidence pack
                // - If assistant calls more tools → loop back
                edge((nodeSendToolResult forwardTo nodeRequestEvidencePack).onAssistantMessage { true })
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
                            - searchIssues / getIssue / getComments: Read from Jira
                            - searchPages / getPage / getChildren: Read from Confluence
                            - searchEmails / getEmail / getThread: Read from Email

                            Your goal: Collect enough evidence to answer the research question.

                            When to stop:
                            1. You have gathered sufficient information
                            2. Multiple tools return similar/redundant info
                            3. Reached max iterations (you'll be stopped automatically)

                            When done, you will be asked to provide an EvidencePack with:
                            - items: list of evidence items, each with source, content, confidence
                            - summary: high-level conclusion of your research

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

                // External read tools
                tools(JiraReadTools(task, jiraService))
                tools(ConfluenceReadTools(task, confluenceService))
                tools(EmailReadTools(task, emailService))

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
}
