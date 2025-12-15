package com.jervis.koog.tools

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import com.jervis.entity.PendingTaskDocument
import com.jervis.graphdb.GraphDBService
import com.jervis.graphdb.model.Direction
import com.jervis.graphdb.model.GraphEdge
import com.jervis.graphdb.model.GraphNode
import com.jervis.graphdb.model.TraversalSpec
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import mu.KotlinLogging

/**
 * GraphTools - Direct Koog ToolSet for Knowledge Graph operations.
 * Provides: Graph node retrieval, relationship traversal, entity search, impact analysis.
 *
 * Knowledge Graph (ArangoDB) stores structured relationships between ALL entities:
 * - CODE: files, classes, methods, packages
 * - VCS: commits, branches, pull requests
 * - ISSUES: Jira tickets, comments, sprints
 * - DOCS: Confluence pages, attachments
 * - COMMUNICATION: emails, Slack/Teams messages, meetings
 * - USERS: people, teams
 * - JOERN: Code Property Graph from static analysis
 *
 * Integration with RAG (Knowledge Base):
 * - Each node has ragChunks: List<String> - chunk IDs from Weaviate
 * - RAG provides semantic search → returns graph nodeKeys
 * - Graph provides structured navigation and relationships
 *
 * See docs/graph-design.md for complete entity and relationship model.
 *
 * Usage in KoogWorkflowAgent/KoogQualifierAgent:
 * ```kotlin
 * tools(GraphTools(taskContext = taskContext, graphService = graphService))
 * ```
 */
@LLMDescription(
    """
Knowledge Graph operations: query nodes, traverse relationships, search entities, analyze impact.
Stores ALL project entities: code, commits, Jira tickets, Confluence docs, emails, meetings.
All operations are per-client isolated. Each node can have RAG chunks for semantic search.""",
)
class GraphTools(
    private val task: PendingTaskDocument,
    private val graphService: GraphDBService,
) : ToolSet {
    companion object {
        private val logger = KotlinLogging.logger {}
    }

    @Tool
    @LLMDescription(
        "Get related nodes from Knowledge Graph. Returns connected entities via relationships. Use for exploring context and dependencies.",
    )
    suspend fun getRelatedNodes(
        @LLMDescription("Node key (e.g., 'file::path/to/file.kt', 'class::ClassName', 'jira::PROJ-123', 'commit::abc123')")
        nodeKey: String,
        @LLMDescription("Relationship direction: 'OUTBOUND', 'INBOUND', 'ANY'")
        direction: String = "ANY",
        @LLMDescription("Edge types to filter (comma-separated, empty = all). Examples: 'mentions,defines,implements,modifies,fixes'")
        edgeTypes: String = "",
        @LLMDescription("Maximum number of nodes to return")
        limit: Int = 20,
    ): String {
        try {
            logger.info {
                "GRAPH_GET_RELATED_START: correlationId=${task.correlationId}, node='$nodeKey', direction=$direction, edgeTypes='$edgeTypes', limit=$limit"
            }
            val clientId = task.clientId

            val dir =
                when (direction.uppercase()) {
                    "OUTBOUND" -> Direction.OUTBOUND
                    "INBOUND" -> Direction.INBOUND
                    "ANY" -> Direction.ANY
                    else -> throw IllegalStateException("GRAPH_INVALID_DIRECTION: Use OUTBOUND, INBOUND, or ANY")
                }

            val edgeList =
                if (edgeTypes.isBlank()) {
                    emptyList()
                } else {
                    edgeTypes.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                }

            val nodes =
                graphService.getRelated(
                    clientId = clientId,
                    nodeKey = nodeKey,
                    edgeTypes = edgeList,
                    direction = dir,
                    limit = limit,
                )

            if (nodes.isEmpty()) {
                return "GRAPH_NO_RESULTS: No related nodes found for key='$nodeKey', direction=$direction, edgeTypes=$edgeTypes"
            }

            return buildString {
                appendLine("GRAPH_RELATED_NODES: Found ${nodes.size} related nodes")
                appendLine("Source: $nodeKey")
                appendLine("Direction: $direction")
                if (edgeList.isNotEmpty()) {
                    appendLine("Edge types: ${edgeList.joinToString(", ")}")
                }
                appendLine()
                nodes.forEachIndexed { idx, node ->
                    appendLine("─────────────────────────────────────────")
                    appendLine("Node #${idx + 1}")
                    appendLine("Key: ${node.key}")
                    appendLine("Type: ${node.entityType}")
                    if (node.ragChunks.isNotEmpty()) {
                        appendLine("RAG chunks: ${node.ragChunks.size}")
                    }
                    appendLine()
                }
            }
        } catch (e: IllegalStateException) {
            throw e
        } catch (e: Exception) {
            throw IllegalStateException("GRAPH_QUERY_FAILED: ${e.message}", e)
        }
    }

    @Tool
    @LLMDescription("Get specific node from Knowledge Graph by key. Returns node properties and metadata.")
    suspend fun getNode(
        @LLMDescription("Node key (e.g., 'file::path/to/file.kt', 'class::ClassName', 'jira::PROJ-123')")
        nodeKey: String,
    ): String {
        try {
            val clientId = task.clientId

            val nodes =
                graphService.getRelated(
                    clientId = clientId,
                    nodeKey = nodeKey,
                    edgeTypes = emptyList(),
                    direction = Direction.ANY,
                    limit = 1,
                )

            if (nodes.isEmpty()) {
                return "GRAPH_NODE_NOT_FOUND: Node with key='$nodeKey' does not exist"
            }

            val node = nodes.first()

            return buildString {
                appendLine("GRAPH_NODE: $nodeKey")
                appendLine("Type: ${node.entityType}")
                appendLine()
                appendLine("Properties:")
                if (node.ragChunks.isNotEmpty()) {
                    appendLine()
                    appendLine("RAG chunks: ${node.ragChunks.size} chunks available for semantic search")
                }
            }
        } catch (e: Exception) {
            throw IllegalStateException("GRAPH_GET_NODE_FAILED: ${e.message}", e)
        }
    }

    @Tool
    @LLMDescription("Upsert node in Knowledge Graph. Creates or updates node with properties. Use to store structured knowledge.")
    suspend fun upsertNode(
        @LLMDescription(
            "Node key (must be unique per client). Format: 'type::identifier' (e.g., 'jira::PROJ-123', 'file::src/main/Service.kt')",
        )
        nodeKey: String,
        @LLMDescription("Node type: file, class, method, jira_issue, commit, confluence_page, email, slack_message, meeting, user, etc.")
        nodeType: String,
        @LLMDescription("Node properties as key=value lines")
        properties: String,
    ): String {
        try {
            logger.info {
                val propsPreview = properties.lines().count { it.contains('=') }
                "GRAPH_UPSERT_START: correlationId=${task.correlationId}, key='$nodeKey', type='$nodeType', propsLines=$propsPreview"
            }
            val clientId = task.clientId

            val node =
                GraphNode(
                    key = nodeKey,
                    entityType = nodeType,
                )

            val result = graphService.upsertNode(clientId, node)

            return buildString {
                appendLine("GRAPH_NODE_UPSERTED: Node '$nodeKey' ${if (result.created) "created" else "updated"}")
                appendLine("Type: $nodeType")
                if (result.warnings.isNotEmpty()) {
                    appendLine("Warnings: ${result.warnings.joinToString(", ")}")
                }
            }
        } catch (e: Exception) {
            throw IllegalStateException("GRAPH_UPSERT_FAILED: ${e.message}", e)
        }
    }

    @Tool
    @LLMDescription("Create link between two nodes in Knowledge Graph. Use to establish relationships between entities.")
    suspend fun createLink(
        @LLMDescription("Source node key")
        fromKey: String,
        @LLMDescription("Target node key")
        toKey: String,
        @LLMDescription(
            """Edge type (relationship): mentions, defines, implements, modifies, creates, deletes,
calls, extends, fixes, blocks, relates_to, documents, discusses, authored_by, assigned_to, contains, etc.""",
        )
        edgeType: String,
    ): String {
        try {
            logger.info {
                "GRAPH_CREATE_LINK_START: correlationId=${task.correlationId}, from='$fromKey', to='$toKey', type='$edgeType'"
            }
            val clientId = task.clientId

            val edge =
                GraphEdge(
                    edgeType = edgeType,
                    fromKey = fromKey,
                    toKey = toKey,
                )

            val result = graphService.upsertEdge(clientId, edge)

            return buildString {
                appendLine("GRAPH_LINK_CREATED: $fromKey --[$edgeType]--> $toKey")
                appendLine("Status: ${if (result.created) "new link created" else "existing link updated"}")
                if (result.warnings.isNotEmpty()) {
                    appendLine("Warnings: ${result.warnings.joinToString(", ")}")
                }
            }
        } catch (e: Exception) {
            throw IllegalStateException("GRAPH_LINK_FAILED: ${e.message}", e)
        }
    }

    @Tool
    @LLMDescription(
        """Traverse Knowledge Graph from starting node. Explores multi-hop relationships to discover connected entities.
Use for impact analysis, dependency chains, requirement tracing.""",
    )
    suspend fun traverseGraph(
        @LLMDescription("Starting node key (e.g., 'method::UserService.login', 'jira::PROJ-123')")
        startKey: String,
        @LLMDescription("Maximum depth to traverse (1-5). Higher = more connections but slower.")
        maxDepth: Int = 2,
        @LLMDescription("Edge types to follow (comma-separated, empty = all). Examples: 'calls,modifies,implements'")
        edgeTypes: String = "",
        @LLMDescription("Maximum total nodes to return")
        limit: Int = 50,
    ): String {
        try {
            val clientId = task.clientId

            val edgeList =
                if (edgeTypes.isBlank()) {
                    emptyList()
                } else {
                    edgeTypes.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                }

            val spec =
                TraversalSpec(
                    maxDepth = maxDepth.coerceIn(1, 5),
                    edgeTypes = edgeList,
                )

            val nodes =
                graphService
                    .traverse(clientId, startKey, spec)
                    .take(limit)
                    .toList()

            if (nodes.isEmpty()) {
                return "GRAPH_TRAVERSE_EMPTY: No nodes found traversing from '$startKey'"
            }

            return buildString {
                appendLine("GRAPH_TRAVERSAL: Found ${nodes.size} connected nodes")
                appendLine("Start: $startKey")
                appendLine("Max depth: $maxDepth")
                if (edgeList.isNotEmpty()) {
                    appendLine("Following edges: ${edgeList.joinToString(", ")}")
                }
                appendLine()

                // Group by entity type for better readability
                nodes.groupBy { it.entityType }.forEach { (type, typeNodes) ->
                    appendLine("═══════════════════════════════════════")
                    appendLine("Entity type: $type (${typeNodes.size} nodes)")
                    appendLine("═══════════════════════════════════════")
                    typeNodes.take(10).forEachIndexed { idx, node ->
                        appendLine("${idx + 1}. ${node.key}")
                    }
                    if (typeNodes.size > 10) {
                        appendLine("   ... and ${typeNodes.size - 10} more")
                    }
                    appendLine()
                }
            }
        } catch (e: Exception) {
            throw IllegalStateException("GRAPH_TRAVERSE_FAILED: ${e.message}", e)
        }
    }

    @Tool
    @LLMDescription(
        """Analyze impact of changes to a code entity. Shows what will be affected if this entity changes.
Returns: callers, dependents, tests, related tickets, documentation.""",
    )
    suspend fun analyzeImpact(
        @LLMDescription("Code entity key to analyze (e.g., 'method::UserService.authenticate', 'class::PaymentProcessor')")
        entityKey: String,
        @LLMDescription("Include indirect impacts? If true, analyzes callers-of-callers. Default false.")
        includeIndirect: Boolean = false,
    ): String {
        try {
            val clientId = task.clientId

            val result = StringBuilder()
            result.appendLine("IMPACT_ANALYSIS: $entityKey")
            result.appendLine("═════════════════════════════════════════")
            result.appendLine()

            // 1. Direct callers
            val directCallers =
                graphService.getRelated(
                    clientId = clientId,
                    nodeKey = entityKey,
                    edgeTypes = listOf("calls", "accesses", "uses"),
                    direction = Direction.INBOUND,
                    limit = 20,
                )

            result.appendLine("DIRECT CALLERS/USERS: ${directCallers.size}")
            directCallers.take(10).forEach { node ->
                result.appendLine("  - ${node.key}")
            }
            if (directCallers.size > 10) {
                result.appendLine("  ... and ${directCallers.size - 10} more")
            }
            result.appendLine()

            // 2. Tests
            val tests =
                graphService.getRelated(
                    clientId = clientId,
                    nodeKey = entityKey,
                    edgeTypes = listOf("tests"),
                    direction = Direction.INBOUND,
                    limit = 10,
                )

            result.appendLine("TESTS: ${tests.size}")
            tests.forEach { node ->
                result.appendLine("  - ${node.key}")
            }
            result.appendLine()

            // 3. Related Jira tickets
            val tickets =
                graphService
                    .getRelated(
                        clientId = clientId,
                        nodeKey = entityKey,
                        edgeTypes = listOf("mentions_method", "mentions_class", "mentions_file"),
                        direction = Direction.INBOUND,
                        limit = 10,
                    ).filter { it.entityType == "jira_issue" }

            result.appendLine("RELATED JIRA TICKETS: ${tickets.size}")
            result.appendLine()

            // 4. Documentation
            val docs =
                graphService
                    .getRelated(
                        clientId = clientId,
                        nodeKey = entityKey,
                        edgeTypes = listOf("documents"),
                        direction = Direction.INBOUND,
                        limit = 5,
                    ).filter { it.entityType == "confluence_page" }

            result.appendLine("DOCUMENTATION: ${docs.size}")
            result.appendLine()

            // 5. Indirect impacts (if requested)
            if (includeIndirect && directCallers.isNotEmpty()) {
                result.appendLine("INDIRECT IMPACTS (callers of callers):")
                val indirectCallers = mutableSetOf<String>()
                for (caller in directCallers.take(5)) {
                    val secondLevel =
                        graphService.getRelated(
                            clientId = clientId,
                            nodeKey = caller.key,
                            edgeTypes = listOf("calls"),
                            direction = Direction.INBOUND,
                            limit = 5,
                        )
                    secondLevel.forEach { indirectCallers.add(it.key) }
                }
                result.appendLine("  Found ${indirectCallers.size} indirect callers")
                indirectCallers.take(10).forEach { key ->
                    result.appendLine("  - $key")
                }
                result.appendLine()
            }

            result.appendLine("═════════════════════════════════════════")
            result.appendLine(
                "SUMMARY: ${directCallers.size} direct dependencies, ${tests.size} tests, ${tickets.size} tickets, ${docs.size} docs",
            )

            return result.toString()
        } catch (e: Exception) {
            throw IllegalStateException("IMPACT_ANALYSIS_FAILED: ${e.message}", e)
        }
    }

    @Tool
    @LLMDescription(
        """Trace requirement from specification to implementation.
Finds path: Jira ticket → commits → code changes → tests → documentation.""",
    )
    suspend fun traceRequirement(
        @LLMDescription("Requirement identifier (e.g., 'jira::PROJ-123', 'confluence::SPACE::123')")
        requirementKey: String,
    ): String {
        try {
            val clientId = task.clientId

            val result = StringBuilder()
            result.appendLine("REQUIREMENT_TRACE: $requirementKey")
            result.appendLine("═════════════════════════════════════════")
            result.appendLine()

            // 1. Get requirement details
            val requirement =
                graphService
                    .getRelated(
                        clientId = clientId,
                        nodeKey = requirementKey,
                        edgeTypes = emptyList(),
                        direction = Direction.ANY,
                        limit = 1,
                    ).firstOrNull()

            if (requirement == null) {
                return "REQUIREMENT_NOT_FOUND: $requirementKey does not exist in graph"
            }

            result.appendLine("REQUIREMENT:")
            result.appendLine("  Type: ${requirement.entityType}")
            result.appendLine()

            // 2. Find implementing commits
            val commits =
                graphService
                    .getRelated(
                        clientId = clientId,
                        nodeKey = requirementKey,
                        edgeTypes = listOf("fixes", "implements", "addresses"),
                        direction = Direction.INBOUND,
                        limit = 20,
                    ).filter { it.entityType == "commit" }

            result.appendLine("IMPLEMENTING COMMITS: ${commits.size}")
            commits.take(5).forEach { commit ->
                result.appendLine("  - ${commit.key}")
            }
            result.appendLine()

            // 3. Find modified code
            val modifiedCode = mutableListOf<GraphNode>()
            for (commit in commits) {
                val nodes =
                    graphService.getRelated(
                        clientId = clientId,
                        nodeKey = commit.key,
                        edgeTypes = listOf("modifies", "modifies_method", "modifies_class"),
                        direction = Direction.OUTBOUND,
                        limit = 10,
                    )
                modifiedCode.addAll(nodes)
            }
            val distinctModifiedCode = modifiedCode.distinctBy { it.key }

            result.appendLine("MODIFIED CODE: ${distinctModifiedCode.size} entities")
            distinctModifiedCode.groupBy { it.entityType }.forEach { (type, nodes) ->
                result.appendLine("  $type: ${nodes.size}")
                nodes.take(3).forEach { node ->
                    result.appendLine("    - ${node.key}")
                }
            }
            result.appendLine()

            // 4. Find related tests
            val allTests = mutableListOf<GraphNode>()
            for (code in distinctModifiedCode) {
                val nodes =
                    graphService.getRelated(
                        clientId = clientId,
                        nodeKey = code.key,
                        edgeTypes = listOf("tests"),
                        direction = Direction.INBOUND,
                        limit = 5,
                    )
                allTests.addAll(nodes)
            }
            val tests = allTests.distinctBy { it.key }

            result.appendLine("TESTS: ${tests.size}")
            tests.take(5).forEach { test ->
                result.appendLine("  - ${test.key}")
            }
            result.appendLine()

            // 5. Find documentation
            val docs =
                graphService
                    .getRelated(
                        clientId = clientId,
                        nodeKey = requirementKey,
                        edgeTypes = listOf("documents", "references"),
                        direction = Direction.INBOUND,
                        limit = 5,
                    ).filter { it.entityType == "confluence_page" }

            result.appendLine("DOCUMENTATION: ${docs.size}")
            result.appendLine()

            result.appendLine("═════════════════════════════════════════")
            result.appendLine("TRACE COMPLETE: ${commits.size} commits → ${distinctModifiedCode.size} code entities → ${tests.size} tests")

            return result.toString()
        } catch (e: Exception) {
            throw IllegalStateException("REQUIREMENT_TRACE_FAILED: ${e.message}", e)
        }
    }
}
