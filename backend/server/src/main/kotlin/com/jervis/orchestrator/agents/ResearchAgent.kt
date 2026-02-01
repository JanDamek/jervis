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
import com.jervis.common.client.IJoernClient
import com.jervis.configuration.RpcReconnectHandler
import com.jervis.entity.TaskDocument
import com.jervis.integration.bugtracker.BugTrackerService
import com.jervis.integration.wiki.WikiService
import com.jervis.koog.KoogPromptExecutorFactory
import com.jervis.koog.SmartModelSelector
import com.jervis.koog.tools.KnowledgeStorageTools
import com.jervis.koog.tools.analysis.JoernTools
import com.jervis.koog.tools.analysis.LogSearchTools
import com.jervis.koog.tools.external.BugTrackerReadTools
import com.jervis.koog.tools.external.EmailReadTools
import com.jervis.koog.tools.external.IssueTrackerTool
import com.jervis.koog.tools.external.WikiReadTools
import com.jervis.orchestrator.model.ContextPack
import com.jervis.orchestrator.model.EvidencePack
import com.jervis.orchestrator.prompts.NoGuessingDirectives
import com.jervis.knowledgebase.KnowledgeService
import com.jervis.knowledgebase.internal.graphdb.GraphDBService
import com.jervis.service.email.EmailService
import com.jervis.service.project.ProjectService
import com.jervis.service.storage.DirectoryStructureService
import mu.KotlinLogging
import org.springframework.stereotype.Component
import java.nio.file.Paths

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
    private val jiraService: BugTrackerService,
    private val confluenceService: WikiService,
    private val emailService: EmailService,
    private val joernClient: IJoernClient,
    private val projectService: ProjectService,
    private val directoryStructureService: DirectoryStructureService,
    private val reconnectHandler: RpcReconnectHandler,
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

        val exampleEvidencePack =
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
            )

        val agentStrategy =
            strategy<String, EvidencePack>("Research Loop") {
                // Koog pattern: LLM request → tool execution → send results → repeat or finish
                val nodeLLMRequest by nodeLLMRequest()
                val nodeExecuteTool by nodeExecuteTool()
                val nodeSendToolResult by nodeLLMSendToolResult()
                val nodeCompressHistory by nodeLLMCompressHistory<ai.koog.agents.core.environment.ReceivedToolResult>()

                // Final node that builds EvidencePack from conversation using structured output
                val nodeRequestEvidencePack by
                    nodeLLMRequestStructured<EvidencePack>(
                        examples = listOf(exampleEvidencePack),
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

                // Compress history if too large to keep core analytical reasoning in context.
                // Qwen3 handles up to 256k, but we compress to keep performance high
                // and focus on current evidence gathering goal.
                edge(
                    (nodeExecuteTool forwardTo nodeCompressHistory)
                        .onCondition { llm.readSession { prompt.messages.size > 50 } },
                )
                edge(nodeCompressHistory forwardTo nodeSendToolResult)

                edge(
                    (nodeExecuteTool forwardTo nodeSendToolResult)
                        .onCondition { llm.readSession { prompt.messages.size <= 50 } },
                )

                // After sending tool result:
                // - If assistant says "done" → request structured evidence pack
                // - If assistant calls more tools → loop back
                edge((nodeSendToolResult forwardTo nodeRequestEvidencePack).onAssistantMessage { true })
                edge((nodeSendToolResult forwardTo nodeExecuteTool).onToolCall { true })
            }

        val model =
            smartModelSelector.selectModelBlocking(
                baseModelName = SmartModelSelector.BaseModelTypeEnum.AGENT,
                inputContent = task.content,
                projectId = task.projectId,
            )

        val agentConfig =
            AIAgentConfig(
                prompt =
                    Prompt.build("research-agent") {
                        system(
                            """
                            You are Research Agent. Gather evidence by calling available tools.

                            Your goal: Collect enough evidence to answer the research question.

                            ${NoGuessingDirectives.CRITICAL_RULES}

                            EVIDENCE GATHERING STRATEGY:
                            1. KNOWLEDGE STORAGE: Use semantic search tools to find relevant documents and code in the knowledge base.
                            2. GRAPH AUGMENTATION: If you find a key entity (ticket ID, file path, user, product, order), explore its relationships and connections to discover context.
                            3. ISSUE TRACKING: Search and read from issue tracking systems to understand bugs, tasks, and their history.
                            4. DOCUMENTATION: Search and read documentation platforms for specifications, decisions, and guidelines.
                            5. COMMUNICATION: Search email and messaging systems for historical discussions and decisions.
                            6. CODE ANALYSIS: Use static analysis tools for deep code structure understanding (relationships between functions, data flow).
                            7. RUNTIME ANALYSIS: Search application logs for debugging and runtime behavior understanding.

                            KNOWLEDGE BASE RULES:
                            - Every RAG chunk is anchored to a GraphNode.
                            - When semantic search returns results, explore the mainNodeKey to discover neighboring entities in the graph.
                            - Semantic partitioning in RAG is thematic; look for context headers in chunks.

                            STOPPING CRITERION:
                            - When you have enough information to answer the original request, stop gathering and prepare to summarize.
                            - You will be asked to provide an EvidencePack with:
                              * items: list of evidence items, each with source, content, confidence
                              * summary: high-level conclusion of your research

                            Be thorough but efficient. Don't repeat the same query.
                            """.trimIndent(),
                        )
                    },
                model = model,
                maxAgentIterations = 100, // Max research iterations
            )

        val toolRegistry =
            ToolRegistry {
                // Knowledge tools (RAG + GraphDB)
                tools(KnowledgeStorageTools(task, knowledgeService, graphDBService))

                // External read tools
                tools(BugTrackerReadTools(task, jiraService))
                tools(WikiReadTools(task, confluenceService))
                tools(EmailReadTools(task, emailService))

                // Analysis tools
                tools(JoernTools(task, joernClient, projectService, directoryStructureService, reconnectHandler))
                tools(LogSearchTools(task, Paths.get("logs")))

                tools(
                    IssueTrackerTool(
                        task,
                        jiraService,
                        com.jervis.orchestrator.bugtracker
                            .BugTrackerAdapter(jiraService),
                    ),
                )
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
                    appendLine("  knownFacts: ${context.knownFacts.joinToString("; ")}")
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
