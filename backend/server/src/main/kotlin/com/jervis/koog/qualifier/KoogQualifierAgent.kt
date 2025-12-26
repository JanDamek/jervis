package com.jervis.koog.qualifier

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.dsl.builder.forwardTo
import ai.koog.agents.core.dsl.builder.strategy
import ai.koog.agents.core.dsl.extension.nodeExecuteTool
import ai.koog.agents.core.dsl.extension.nodeLLMRequest
import ai.koog.agents.core.dsl.extension.nodeLLMRequestStructured
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
import com.jervis.koog.qualifier.types.ContentTypeDetection
import com.jervis.koog.tools.KnowledgeStorageTools
import com.jervis.koog.tools.qualifier.QualifierRoutingTools
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
 * - LLM outputs are simple POJOs: strings + lists (no deep nesting).
 * - Routing is done via tool call inside Koog.
 */
@Service
class KoogQualifierAgent(
    private val promptExecutorFactory: KoogPromptExecutorFactory,
    private val koogProperties: KoogProperties,
    private val providerSelector: OllamaProviderSelector,
    private val modelSelector: SmartModelSelector,
    private val taskService: TaskService,
    private val linkContentService: LinkContentService,
    private val knowledgeService: KnowledgeService,
    private val graphDBService: GraphDBService,
    private val indexedLinkService: IndexedLinkService,
    private val connectionService: ConnectionService,
) {
    private val logger = KotlinLogging.logger {}

    // ----------------------------
    // Pipeline ctx (internal)
    // ----------------------------
    private data class PipelineCtx(
        var contentType: ContentType = ContentType.GENERIC,
        var basicInfo: String = "",
        val pendingGroups: ArrayDeque<String> = ArrayDeque(),
        var baseNodeKey: String = "",
    )

    private enum class ContentType {
        EMAIL,
        JIRA,
        CONFLUENCE,
        LOG,
        GENERIC,
    }

    fun create(task: TaskDocument): AIAgent<String, String> {
        val ctx = PipelineCtx()

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
            strategy<String, String>("Jervis Qualifier Strategy") {
                // ----------------------------
                // Type detection
                // ----------------------------
                val nodeTypePrompt by node<String, String>(name = "📋 Type prompt") { input ->
                    buildTypePrompt(input)
                }

                val nodeDetectType by nodeLLMRequestStructured<ContentTypeDetection>(
                    name = "📋 Detect type",
                    examples =
                        listOf(
                            ContentTypeDetection("EMAIL", "Email-like headers/subject/pickup."),
                            ContentTypeDetection("JIRA", "Issue key/status/assignee fields."),
                            ContentTypeDetection("CONFLUENCE", "Documentation/page structure."),
                            ContentTypeDetection("LOG", "Timestamps/stack traces."),
                        ),
                )

                val nodeApplyType by node<Result<StructuredResponse<ContentTypeDetection>>, ContentType>(name = "📋 Apply type") { r ->
                    val t =
                        r
                            .getOrNull()
                            ?.structure
                            ?.contentType
                            ?.trim()
                            ?.uppercase()
                            .orEmpty()
                    val type =
                        when (t) {
                            "EMAIL" -> ContentType.EMAIL
                            "JIRA" -> ContentType.JIRA
                            "CONFLUENCE" -> ContentType.CONFLUENCE
                            "LOG" -> ContentType.LOG
                            else -> ContentType.GENERIC
                        }
                    ctx.contentType = type
                    ctx.baseNodeKey = computeBaseNodeKey(task, type)
                    logger.info {
                        "📋 CONTENT_TYPE | correlationId=${task.correlationId} | contentType=$type | baseNodeKey=${ctx.baseNodeKey}"
                    }
                    type
                }

                // ----------------------------
                // Split
                // ----------------------------
                val nodeSplitPrompt by node<ContentType, String>(name = "🧩 Split prompt") { type ->
                    buildSplitPrompt(type = type, fullText = task.content)
                }

                val nodeSplit by nodeLLMRequestStructured<ThematicSplit>(
                    name = "🧩 Split",
                    examples =
                        listOf(
                            ThematicSplit(
                                basicInfo = "Email about order pickup.",
                                groups = listOf("Pickup details", "Order items"),
                            ),
                        ),
                )

                val nodeApplySplit by node<Result<StructuredResponse<ThematicSplit>>, Unit>(name = "🧩 Apply split") { r ->
                    val split = r.getOrNull()?.structure
                    ctx.basicInfo = split?.basicInfo?.trim().orEmpty()
                    ctx.pendingGroups.clear()
                    split
                        ?.groups
                        .orEmpty()
                        .map { it.trim() }
                        .filter { it.isNotBlank() }
                        .forEach { ctx.pendingGroups.addLast(it) }
                    logger.info { "📦 INDEXING_CTX | correlationId=${task.correlationId} | groups=${ctx.pendingGroups.size}" }
                }

                // ----------------------------
                // Loop (per-group indexing + persist)
                // ----------------------------
                val nodeNextGroup by node<Unit, String?>(name = "➡️ Next group") {
                    if (ctx.pendingGroups.isEmpty()) null else ctx.pendingGroups.removeFirst()
                }

                val nodeGroupPrompt by node<String?, String>(name = "🧱 Group prompt") { group ->
                    buildGroupIndexingPrompt(
                        type = ctx.contentType,
                        basicInfo = ctx.basicInfo,
                        group = group.orEmpty(),
                        baseNodeKey = ctx.baseNodeKey,
                    )
                }

                val nodeIndexGroup by nodeLLMRequestStructured<GroupIndexing>(
                    name = "🧱 Index group",
                    examples =
                        listOf(
                            GroupIndexing(
                                title = "Pickup details",
                                chunks = listOf("Pickup code and deadline."),
                                graph =
                                    listOf(
                                        "node:order:574377215",
                                        "node:code:720_632",
                                        "node:time:2025-12-12T23:59",
                                        "edge:order:574377215|has_code|code:720_632",
                                        "edge:order:574377215|pickup_deadline|time:2025-12-12T23:59",
                                    ),
                            ),
                        ),
                )

                val nodeToPersistInput by node<Result<StructuredResponse<GroupIndexing>>, String>(name = "🧱 Persist input") { r ->
                    val g = r.getOrNull()?.structure
                    val title = g?.title?.trim().orEmpty()
                    val chunks = g?.chunks.orEmpty().joinToString("\n- ", prefix = "- ")
                    val graphLines = g?.graph.orEmpty().joinToString("\n- ", prefix = "- ")

                    buildString {
                        appendLine("Store this group indexing result.")
                        appendLine("baseNodeKey: ${ctx.baseNodeKey}")
                        appendLine("title: $title")
                        appendLine()
                        appendLine("chunks:")
                        appendLine(chunks)
                        appendLine()
                        appendLine("graph:")
                        appendLine(graphLines)
                    }
                }

                val indexingSubgraph by subgraph<String, Unit>(
                    name = "🧱 Persist group",
                    tools = storageTools,
                ) {
                    val ask by nodeLLMRequest()
                    val exec by nodeExecuteTool()

                    edge(nodeStart forwardTo ask)
                    edge(ask forwardTo exec onToolCall { true })
                    edge((ask forwardTo nodeFinish) onAssistantMessage { true } transformed { Unit })
                    edge((exec forwardTo nodeFinish) transformed { Unit })
                }

                // ----------------------------
                // Final routing
                // ----------------------------
                val nodeRoutingPrompt by node<Unit, String>(name = "🎯 Routing prompt") {
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
                    edge((ask forwardTo nodeFinish) onAssistantMessage { true } transformed { it.toString() })
                    edge((exec forwardTo nodeFinish) transformed { "OK" })
                }

                // ----------------------------
                // Wiring
                // ----------------------------
                edge(nodeStart forwardTo nodeTypePrompt)
                edge(nodeTypePrompt forwardTo nodeDetectType)
                edge(nodeDetectType forwardTo nodeApplyType)

                edge(nodeApplyType forwardTo nodeSplitPrompt)
                edge(nodeSplitPrompt forwardTo nodeSplit)
                edge(nodeSplit forwardTo nodeApplySplit)

                edge(nodeApplySplit forwardTo nodeNextGroup)

                edge(
                    (nodeNextGroup forwardTo nodeRoutingPrompt)
                        onCondition { it == null }
                        transformed { Unit },
                )

                edge(
                    (nodeNextGroup forwardTo nodeGroupPrompt)
                        onCondition { it != null },
                )

                edge(nodeGroupPrompt forwardTo nodeIndexGroup)
                edge(nodeIndexGroup forwardTo nodeToPersistInput)
                edge(nodeToPersistInput forwardTo indexingSubgraph)
                edge(indexingSubgraph forwardTo nodeNextGroup)

                edge(nodeRoutingPrompt forwardTo routingSubgraph)
                edge(routingSubgraph forwardTo nodeFinish)
            }

        val model =
            modelSelector.selectModel(
                baseModelName = SmartModelSelector.BaseModelTypeEnum.AGENT,
                inputContent = task.content,
            )

        val agentConfig =
            AIAgentConfig(
                model = model,
                maxAgentIterations = koogProperties.maxIterations,
                prompt =
                    prompt("Jervis Qualifier Agent") {
                        system("You are a text qualifier agent. Your task is to process text and create a structured output.")
                    },
            )

        return AIAgent(
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
    }

    private fun buildTypePrompt(fullText: String): String =
        buildString {
            appendLine("Pick one type: EMAIL, JIRA, CONFLUENCE, LOG, GENERIC")
            appendLine("CONTENT:")
            appendLine(fullText.take(6000))
        }

    private fun buildSplitPrompt(
        type: ContentType,
        fullText: String,
    ): String =
        buildString {
            appendLine("Create basicInfo + thematic groups.")
            appendLine("basicInfo: a short summary of the whole input (keep it compact).")
            appendLine("groups: split into thematic blocks that TOGETHER cover the entire content.")
            appendLine("Create as many groups as needed. Do not omit content.")
            appendLine("Each group MUST include the original facts/sentences (not just a title).")
            appendLine("Prefer smaller groups (~1–3k chars each). If needed, create more groups rather than dropping details.")
            appendLine("Include all important numbers, dates, addresses, codes.")
            appendLine("CONTENT TYPE: $type")
            appendLine("CONTENT:")
            appendLine(fullText.take(9000))
        }

    private fun buildGroupIndexingPrompt(
        type: ContentType,
        basicInfo: String,
        group: String,
        baseNodeKey: String,
    ): String =
        buildString {
            appendLine("Create: title + chunks + graph.")
            appendLine("chunks: break the GROUP into self-contained pieces that cover the whole group.")
            appendLine("Create as many chunks as needed. Do not omit facts.")
            appendLine("Prefer chunk size ~200–600 chars.")
            appendLine("graph lines:")
            appendLine("- node:type:key")
            appendLine("- edge:from|rel|to")
            appendLine("Include time:* and location:* nodes when present (dates, deadlines, addresses).")
            appendLine("Graph must include ALL important entities and relations implied by the text (no limits on nodes/edges).")
            appendLine("Base node key: $baseNodeKey")
            appendLine("CONTENT TYPE: $type")
            appendLine()
            appendLine("BASIC INFO:")
            appendLine(basicInfo.take(800))
            appendLine()
            appendLine("GROUP:")
            appendLine(group.take(2500))
        }

    private fun computeBaseNodeKey(
        task: TaskDocument,
        type: ContentType,
    ): String {
        val suffix =
            task.correlationId
                .ifBlank { "noid" }
                .takeLast(24)
                .lowercase()
        return when (type) {
            ContentType.EMAIL -> "email_$suffix"
            ContentType.JIRA -> "jira_$suffix"
            ContentType.CONFLUENCE -> "confluence_$suffix"
            ContentType.LOG -> "log_$suffix"
            ContentType.GENERIC -> "doc_$suffix"
        }
    }
}

// ----------------------------
// Simple structured outputs (POJOs)
// ----------------------------

@Serializable
data class ContentTypeDetection(
    val contentType: String,
    val reason: String,
)

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
