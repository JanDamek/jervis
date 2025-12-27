package com.jervis.koog.qualifier

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
import ai.koog.agents.core.feature.handler.agent.AgentStartingContext
import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.core.tools.reflect.asTools
import ai.koog.agents.features.eventHandler.feature.EventHandler
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.structure.StructuredResponse
import com.jervis.configuration.properties.KoogProperties
import com.jervis.entity.TaskDocument
import com.jervis.koog.KoogPromptExecutorFactory
import com.jervis.koog.OllamaProviderSelector
import com.jervis.koog.SmartModelSelector
import com.jervis.koog.tools.KnowledgeStorageTools
import com.jervis.koog.tools.qualifier.QualifierRoutingTools
import com.jervis.koog.vision.AttachmentDescription
import com.jervis.koog.vision.VisionAnalysisAgent
import com.jervis.rag.KnowledgeService
import com.jervis.rag.internal.graphdb.GraphDBService
import com.jervis.service.background.TaskService
import com.jervis.service.connection.ConnectionService
import com.jervis.service.link.IndexedLinkService
import com.jervis.service.link.LinkContentService
import kotlinx.serialization.Serializable
import mu.KotlinLogging
import org.springframework.stereotype.Service

/**
 * KoogQualifierAgent
 *
 * Goals (per Koog rules):
 * - Short, tool-agnostic prompts.
 * - Tools are scoped ONLY via subgraphs (no "tool format" instructions in prompts).
 * - LLM outputs are simple POJOs: strings and lists (no deep nesting).
 * - Routing is done via tool call inside Koog.
 */
@Service
class KoogQualifierAgent(
    private val promptExecutorFactory: KoogPromptExecutorFactory,
    private val koogProperties: KoogProperties,
    private val providerSelector: OllamaProviderSelector,
    private val modelSelector: SmartModelSelector,
    private val visionAgent: VisionAnalysisAgent,
    private val taskService: TaskService,
    private val linkContentService: LinkContentService,
    private val knowledgeService: KnowledgeService,
    private val graphDBService: GraphDBService,
    private val indexedLinkService: IndexedLinkService,
    private val connectionService: ConnectionService,
) {
    private val logger = KotlinLogging.logger {}

    private data class PipelineCtx(
        var basicInfo: String = "",
        val pendingGroups: ArrayDeque<String> = ArrayDeque(),
    )

    data class AgentWithInput(
        val agent: AIAgent<SplitInput, String>,
        val input: SplitInput,
    )

    suspend fun create(task: TaskDocument): AgentWithInput {
        val ctx = PipelineCtx()

        val visionResult = visionAgent.analyze(task.attachments)

        logger.info {
            "VISION_COMPLETE | correlationId=${task.correlationId} | " +
                "contentLength=${task.content.length} | attachments=${visionResult.descriptions.size}"
        }

        val routingTool =
            QualifierRoutingTools(
                task,
                taskService,
                linkContentService,
                indexedLinkService,
                connectionService,
            )

        val indexingTool = KnowledgeStorageTools(task, knowledgeService, graphDBService)

        val routingTools: List<Tool<*, *>> = routingTool.asTools()
        val storageTools: List<Tool<*, *>> = indexingTool.asTools()

        val toolRegistry =
            ToolRegistry {
                routingTools.forEach { tool(it) }
                storageTools.forEach { tool(it) }
            }

        val graphStrategy =
            strategy<SplitInput, String>("Jervis Qualifier Strategy") {
                val nodeSplitPrompt by node<SplitInput, String>(name = "Split prompt") { input ->
                    buildString {
                        appendLine("Content to split:")
                        appendLine(input.content)
                        if (input.visionDescriptions.isNotEmpty()) {
                            appendLine()
                            appendLine("Vision analysis:")
                            input.visionDescriptions.forEach { desc ->
                                appendLine("- ${desc.filename}: ${desc.description}")
                            }
                        }
                    }
                }

                val nodeSplit by nodeLLMRequestStructured<ThematicSplit>(
                    name = "Split",
                    examples =
                        listOf(
                            ThematicSplit(
                                basicInfo = "Complete authentication system refactoring with OAuth2 migration, security audit, and deployment plan.",
                                groups =
                                    listOf(
                                        """
                                        Authentication System Refactoring - Complete Project

                                        OAuth2 Implementation: Added OAuth2Provider service supporting Google, GitHub, and Microsoft. Updated LoginController for multiple auth methods including legacy password-based and new OAuth2 flows. Integrated JWT token generation (15-min access, 7-day refresh tokens). Modified AuthConfig for OAuth2 endpoints (/oauth2/authorize, /oauth2/token, /oauth2/callback). Created database tables for OAuth2 state management and provider mappings. Implemented provider-specific adapters.

                                        Testing: Successful login flow with all providers. Load testing: 1000 concurrent auths without degradation. Edge cases tested: expired tokens, invalid state, network failures. 95% code coverage.

                                        Security Review: Fixed CSRF vulnerability in callback handler with state parameter validation. Implemented rate limiting (10 req/min per IP). Added AES-256 encryption for refresh tokens. Secure HTTP-only cookies for token storage. Audit logging for all auth events. Penetration testing scheduled.

                                        Deployment: Q1 2025 phased rollout. Phase 1: Internal employees (week 1-2). Phase 2: Beta customers (week 3-4). Phase 3: General availability (week 5+). Monitoring: auth failure rates, token refresh rates, provider success rates. Alerting: >5% failure rate. Rollback: feature flag flip, 5-min downtime estimate. Backward-compatible migrations.
                                        """.trimIndent(),
                                    ),
                            ),
                        ),
                )

                val nodeApplySplit by node<Result<StructuredResponse<ThematicSplit>>, Unit>(name = "Apply split") { r ->
                    val split = r.getOrNull()?.structure
                    ctx.basicInfo = split?.basicInfo?.trim().orEmpty()
                    ctx.pendingGroups.clear()
                    split
                        ?.groups
                        .orEmpty()
                        .map { it.trim() }
                        .filter { it.isNotBlank() }
                        .forEach { ctx.pendingGroups.addLast(it) }
                    logger.info { "INDEXING_CTX | correlationId=${task.correlationId} | groups=${ctx.pendingGroups.size}" }
                }

                val nodeNextGroup by node<Unit, String?>(name = "Next group") {
                    if (ctx.pendingGroups.isEmpty()) null else ctx.pendingGroups.removeFirst()
                }

                val nodeIndexPrompt by node<String, String>(name = "Index prompt") { group ->
                    buildString {
                        appendLine("Process this group: split into semantic chunks (~400 tokens each) and store each chunk with graph relationships.")
                        appendLine()
                        appendLine("mainNodeKey: ${task.correlationId}")
                        appendLine()
                        appendLine("Basic context:")
                        appendLine(ctx.basicInfo)
                        appendLine()
                        appendLine("Group content:")
                        appendLine(group)
                        if (visionResult.descriptions.isNotEmpty()) {
                            appendLine()
                            appendLine("Vision analysis:")
                            visionResult.descriptions.forEach { desc ->
                                appendLine("- ${desc.filename}: ${desc.description}")
                            }
                        }
                        appendLine()
                        appendLine("For each chunk: call tool with content, mainNodeKey, and graph relationships (format: node:type:key, edge:from|rel|to).")
                        appendLine("When all chunks are stored, respond with 'done'.")
                    }
                }

                val indexingSubgraph by subgraph<String, Unit>(
                    name = "Process group",
                    tools = storageTools,
                ) {
                    val ask by nodeLLMRequest()
                    val exec by nodeExecuteTool()
                    val sendResult by nodeLLMSendToolResult()

                    edge(nodeStart forwardTo ask)
                    edge(ask forwardTo exec onToolCall { true })
                    edge(exec forwardTo sendResult)
                    edge(sendResult forwardTo exec onToolCall { true })  // LLM může zavolat další tool
                    edge((sendResult forwardTo nodeFinish) onAssistantMessage { true } transformed { })
                    edge((ask forwardTo nodeFinish) onAssistantMessage { true } transformed { })
                }

                val nodeRoutingPrompt by node<Unit, String>(name = "Routing prompt") {
                    "Decide the next action and use an available tool."
                }

                val routingSubgraph by subgraph<String, String>(
                    name = "Final Routing",
                    tools = routingTools,
                ) {
                    val ask by nodeLLMRequest()
                    val exec by nodeExecuteTool()

                    edge(nodeStart forwardTo ask)
                    edge(ask forwardTo exec onToolCall { true })
                    edge((ask forwardTo nodeFinish) onAssistantMessage { true } transformed { it })
                    edge((exec forwardTo nodeFinish) transformed { "OK" })
                }

                edge(nodeStart forwardTo nodeSplitPrompt)
                edge(nodeSplitPrompt forwardTo nodeSplit)
                edge(nodeSplit forwardTo nodeApplySplit)

                edge(nodeApplySplit forwardTo nodeNextGroup)

                edge(
                    (nodeNextGroup forwardTo nodeRoutingPrompt)
                        onCondition { it == null }
                        transformed { },
                )

                edge(
                    (nodeNextGroup forwardTo nodeIndexPrompt)
                        onCondition { it != null }
                        transformed { it!! },
                )

                edge(nodeIndexPrompt forwardTo indexingSubgraph)
                edge(indexingSubgraph forwardTo nodeNextGroup)

                edge(nodeRoutingPrompt forwardTo routingSubgraph)
                edge(routingSubgraph forwardTo nodeFinish)
            }

        val visionText = visionResult.descriptions.joinToString("\n") { it.description }
        val totalContext = task.content + "\n" + visionText

        val model =
            modelSelector.selectModel(
                baseModelName = SmartModelSelector.BaseModelTypeEnum.AGENT,
                inputContent = totalContext,
            )

        logger.info {
            "MODEL_SELECTED | correlationId=${task.correlationId} | model=${model.id} | " +
                "contentLength=${task.content.length} | visionLength=${visionText.length}"
        }

        val agentConfig =
            AIAgentConfig(
                model = model,
                maxAgentIterations = koogProperties.maxIterations,
                prompt =
                    prompt("Jervis Qualifier Agent") {
                        system(
                            """
                            You are a text qualifier agent. Process the input and create structured output.

                            When splitting documents into groups:
                            - Each group = ONE COMPLETE THEME
                            - Include ALL content for that theme in the group
                            - Group size: several thousand tokens (up to 16k soft limit)
                            - Groups will be chunked later (~400 tokens per RAG chunk)
                            - ONLY create multiple groups when themes are clearly distinct
                            - JIRA ticket = usually 1 group (unless off-topic comments)
                            - Large email thread = 1 group if one topic
                            - When in doubt: FEWER, LARGER groups
                            """.trimIndent(),
                        )
                    },
            )

        val inputData =
            SplitInput(
                content = task.content,
                visionDescriptions = visionResult.descriptions,
            )

        val agent =
            AIAgent(
                promptExecutor = promptExecutorFactory.getExecutor(providerSelector.getProvider()),
                toolRegistry = toolRegistry,
                strategy = graphStrategy,
                agentConfig = agentConfig,
                installFeatures = {
                    install(feature = EventHandler) {
                        onAgentStarting { ctx: AgentStartingContext ->
                            logger.info { "Agent starting: ${ctx.agent.id}" }
                        }
                    }
                },
            )

        return AgentWithInput(agent, inputData)
    }
}

@Serializable
data class SplitInput(
    val content: String,
    val visionDescriptions: List<AttachmentDescription>,
)

/**
 * Split document into thematic groups.
 *
 * CRITICAL: Each group = ONE COMPLETE THEME
 *
 * Rules:
 * - basicInfo: Brief summary of entire document (1-2 sentences)
 * - groups: Thematic groups. ONE group per theme. Include ALL content for that theme.
 * - Group size: Typically several thousand tokens. Can be up to 16k tokens (soft limit).
 * - Groups are NOT pre-chunked - model will chunk each group later (~400 tokens per RAG chunk)
 *
 * When to create multiple groups:
 * - ONLY when content has clearly distinct themes
 * - Example: JIRA ticket is usually 1 group (unless comments are off-topic)
 * - Example: Large email thread might be 1 group if discussing one topic
 * - Example: GIT diff with auth + UI changes = 2 groups (different themes)
 *
 * When in doubt: Prefer FEWER, LARGER groups. Don't split unnecessarily.
 */
@Serializable
data class ThematicSplit(
    val basicInfo: String,
    val groups: List<String>,
)

/**
 * Graph is line-based to keep it simple:
 * - node:type:key
 * - edge:from|rel|to
 */
@Serializable
data class GroupIndexing(
    val title: String,
    val chunks: List<String>,
    val graph: List<String>,
)
