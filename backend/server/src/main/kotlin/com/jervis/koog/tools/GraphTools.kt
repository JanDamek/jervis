package com.jervis.koog.tools

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import com.jervis.domain.plan.Plan
import com.jervis.graphdb.GraphDBService
import com.jervis.graphdb.model.Direction
import com.jervis.graphdb.model.GraphNode
import com.jervis.graphdb.model.GraphEdge
import com.jervis.graphdb.model.TraversalSpec
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking

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
 * tools(GraphTools(plan = plan, graphService = graphService))
 * ```
 */
@LLMDescription("""
Knowledge Graph operations: query nodes, traverse relationships, search entities, analyze impact.
Stores ALL project entities: code, commits, Jira tickets, Confluence docs, emails, meetings.
All operations are per-client isolated. Each node can have RAG chunks for semantic search.""")
class GraphTools(
    private val plan: Plan,
    private val graphService: GraphDBService,
) : ToolSet {

    @Tool
    @LLMDescription("Get related nodes from Knowledge Graph. Returns connected entities via relationships. Use for exploring context and dependencies.")
    fun getRelatedNodes(
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
            val clientId = plan.clientDocument.id.toHexString()

            val dir = when (direction.uppercase()) {
                "OUTBOUND" -> Direction.OUTBOUND
                "INBOUND" -> Direction.INBOUND
                "ANY" -> Direction.ANY
                else -> throw IllegalStateException("GRAPH_INVALID_DIRECTION: Use OUTBOUND, INBOUND, or ANY")
            }

            val edgeList = if (edgeTypes.isBlank()) {
                emptyList()
            } else {
                edgeTypes.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            }

            val nodes = runBlocking {
                graphService.getRelated(
                    clientId = clientId,
                    nodeKey = nodeKey,
                    edgeTypes = edgeList,
                    direction = dir,
                    limit = limit,
                )
            }

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
                    node.props.forEach { (k, v) ->
                        when (k) {
                            "title", "name", "description", "summary", "message", "subject" -> appendLine("$k: $v")
                        }
                    }
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
    fun getNode(
        @LLMDescription("Node key (e.g., 'file::path/to/file.kt', 'class::ClassName', 'jira::PROJ-123')")
        nodeKey: String,
    ): String {
        try {
            val clientId = plan.clientDocument.id.toHexString()

            // Get via getRelated with limit=1 since there's no direct getNode method
            val nodes = runBlocking {
                graphService.getRelated(
                    clientId = clientId,
                    nodeKey = nodeKey,
                    edgeTypes = emptyList(),
                    direction = Direction.ANY,
                    limit = 1,
                )
            }

            if (nodes.isEmpty()) {
                return "GRAPH_NODE_NOT_FOUND: Node with key='$nodeKey' does not exist"
            }

            val node = nodes.first()

            return buildString {
                appendLine("GRAPH_NODE: $nodeKey")
                appendLine("Type: ${node.entityType}")
                appendLine()
                appendLine("Properties:")
                node.props.forEach { (k, v) ->
                    appendLine("  $k: $v")
                }
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
    fun upsertNode(
        @LLMDescription("Node key (must be unique per client). Format: 'type::identifier' (e.g., 'jira::PROJ-123', 'file::src/main/Service.kt')")
        nodeKey: String,

        @LLMDescription("Node type: file, class, method, jira_issue, commit, confluence_page, email, slack_message, meeting, user, etc.")
        nodeType: String,

        @LLMDescription("Node properties as key=value lines")
        properties: String,
    ): String {
        try {
            val clientId = plan.clientDocument.id.toHexString()

            val props = properties.lines()
                .mapNotNull { line ->
                    val idx = line.indexOf('=')
                    if (idx <= 0) null else line.substring(0, idx).trim() to line.substring(idx + 1).trim()
                }
                .toMap()

            val node = GraphNode(
                key = nodeKey,
                entityType = nodeType,
                props = props,
            )

            val result = runBlocking {
                graphService.upsertNode(clientId, node)
            }

            return buildString {
                appendLine("GRAPH_NODE_UPSERTED: Node '$nodeKey' ${if (result.created) "created" else "updated"}")
                appendLine("Type: $nodeType")
                appendLine("Properties: ${props.size}")
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
    fun createLink(
        @LLMDescription("Source node key")
        fromKey: String,

        @LLMDescription("Target node key")
        toKey: String,

        @LLMDescription("""Edge type (relationship): mentions, defines, implements, modifies, creates, deletes,
calls, extends, fixes, blocks, relates_to, documents, discusses, authored_by, assigned_to, contains, etc.""")
        edgeType: String,

        @LLMDescription("Optional edge properties as key=value lines (e.g., confidence, timestamp, description)")
        properties: String = "",
    ): String {
        try {
            val clientId = plan.clientDocument.id.toHexString()

            val props = if (properties.isBlank()) {
                emptyMap()
            } else {
                properties.lines()
                    .mapNotNull { line ->
                        val idx = line.indexOf('=')
                        if (idx <= 0) null else line.substring(0, idx).trim() to line.substring(idx + 1).trim()
                    }
                    .toMap()
            }

            val edge = GraphEdge(
                edgeType = edgeType,
                fromKey = fromKey,
                toKey = toKey,
                props = props,
            )

            val result = runBlocking {
                graphService.upsertEdge(clientId, edge)
            }

            return buildString {
                appendLine("GRAPH_LINK_CREATED: $fromKey --[$edgeType]--> $toKey")
                appendLine("Status: ${if (result.created) "new link created" else "existing link updated"}")
                if (props.isNotEmpty()) {
                    appendLine("Properties: ${props.size}")
                }
                if (result.warnings.isNotEmpty()) {
                    appendLine("Warnings: ${result.warnings.joinToString(", ")}")
                }
            }
        } catch (e: Exception) {
            throw IllegalStateException("GRAPH_LINK_FAILED: ${e.message}", e)
        }
    }

    @Tool
    @LLMDescription("""Traverse Knowledge Graph from starting node. Explores multi-hop relationships to discover connected entities.
Use for impact analysis, dependency chains, requirement tracing.""")
    fun traverseGraph(
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
            val clientId = plan.clientDocument.id.toHexString()

            val edgeList = if (edgeTypes.isBlank()) {
                emptyList()
            } else {
                edgeTypes.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            }

            val spec = TraversalSpec(
                maxDepth = maxDepth.coerceIn(1, 5),
                edgeTypes = edgeList,
            )

            val nodes = runBlocking {
                graphService.traverse(clientId, startKey, spec)
                    .take(limit)
                    .toList()
            }

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
                        node.props["title"]?.let { appendLine("   Title: $it") }
                        node.props["name"]?.let { appendLine("   Name: $it") }
                        node.props["summary"]?.let { appendLine("   Summary: $it") }
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
    @LLMDescription("""Analyze impact of changes to a code entity. Shows what will be affected if this entity changes.
Returns: callers, dependents, tests, related tickets, documentation.""")
    fun analyzeImpact(
        @LLMDescription("Code entity key to analyze (e.g., 'method::UserService.authenticate', 'class::PaymentProcessor')")
        entityKey: String,

        @LLMDescription("Include indirect impacts? If true, analyzes callers-of-callers. Default false.")
        includeIndirect: Boolean = false,
    ): String {
        try {
            val clientId = plan.clientDocument.id.toHexString()

            val result = StringBuilder()
            result.appendLine("IMPACT_ANALYSIS: $entityKey")
            result.appendLine("═════════════════════════════════════════")
            result.appendLine()

            // 1. Direct callers
            val directCallers = runBlocking {
                graphService.getRelated(
                    clientId = clientId,
                    nodeKey = entityKey,
                    edgeTypes = listOf("calls", "accesses", "uses"),
                    direction = Direction.INBOUND,
                    limit = 20,
                )
            }

            result.appendLine("DIRECT CALLERS/USERS: ${directCallers.size}")
            directCallers.take(10).forEach { node ->
                result.appendLine("  - ${node.key}")
            }
            if (directCallers.size > 10) {
                result.appendLine("  ... and ${directCallers.size - 10} more")
            }
            result.appendLine()

            // 2. Tests
            val tests = runBlocking {
                graphService.getRelated(
                    clientId = clientId,
                    nodeKey = entityKey,
                    edgeTypes = listOf("tests"),
                    direction = Direction.INBOUND,
                    limit = 10,
                )
            }

            result.appendLine("TESTS: ${tests.size}")
            tests.forEach { node ->
                result.appendLine("  - ${node.key}")
            }
            result.appendLine()

            // 3. Related Jira tickets
            val tickets = runBlocking {
                graphService.getRelated(
                    clientId = clientId,
                    nodeKey = entityKey,
                    edgeTypes = listOf("mentions_method", "mentions_class", "mentions_file"),
                    direction = Direction.INBOUND,
                    limit = 10,
                )
            }.filter { it.entityType == "jira_issue" }

            result.appendLine("RELATED JIRA TICKETS: ${tickets.size}")
            tickets.forEach { node ->
                result.appendLine("  - ${node.key}: ${node.props["summary"]}")
            }
            result.appendLine()

            // 4. Documentation
            val docs = runBlocking {
                graphService.getRelated(
                    clientId = clientId,
                    nodeKey = entityKey,
                    edgeTypes = listOf("documents"),
                    direction = Direction.INBOUND,
                    limit = 5,
                )
            }.filter { it.entityType == "confluence_page" }

            result.appendLine("DOCUMENTATION: ${docs.size}")
            docs.forEach { node ->
                result.appendLine("  - ${node.props["title"]}")
            }
            result.appendLine()

            // 5. Indirect impacts (if requested)
            if (includeIndirect && directCallers.isNotEmpty()) {
                result.appendLine("INDIRECT IMPACTS (callers of callers):")
                val indirectCallers = mutableSetOf<String>()
                directCallers.take(5).forEach { caller ->
                    val secondLevel = runBlocking {
                        graphService.getRelated(
                            clientId = clientId,
                            nodeKey = caller.key,
                            edgeTypes = listOf("calls"),
                            direction = Direction.INBOUND,
                            limit = 5,
                        )
                    }
                    secondLevel.forEach { indirectCallers.add(it.key) }
                }
                result.appendLine("  Found ${indirectCallers.size} indirect callers")
                indirectCallers.take(10).forEach { key ->
                    result.appendLine("  - $key")
                }
                result.appendLine()
            }

            result.appendLine("═════════════════════════════════════════")
            result.appendLine("SUMMARY: ${directCallers.size} direct dependencies, ${tests.size} tests, ${tickets.size} tickets, ${docs.size} docs")

            return result.toString()
        } catch (e: Exception) {
            throw IllegalStateException("IMPACT_ANALYSIS_FAILED: ${e.message}", e)
        }
    }

    @Tool
    @LLMDescription("""Trace requirement from specification to implementation.
Finds path: Jira ticket → commits → code changes → tests → documentation.""")
    fun traceRequirement(
        @LLMDescription("Requirement identifier (e.g., 'jira::PROJ-123', 'confluence::SPACE::123')")
        requirementKey: String,
    ): String {
        try {
            val clientId = plan.clientDocument.id.toHexString()

            val result = StringBuilder()
            result.appendLine("REQUIREMENT_TRACE: $requirementKey")
            result.appendLine("═════════════════════════════════════════")
            result.appendLine()

            // 1. Get requirement details
            val requirement = runBlocking {
                graphService.getRelated(
                    clientId = clientId,
                    nodeKey = requirementKey,
                    edgeTypes = emptyList(),
                    direction = Direction.ANY,
                    limit = 1,
                )
            }.firstOrNull()

            if (requirement == null) {
                return "REQUIREMENT_NOT_FOUND: $requirementKey does not exist in graph"
            }

            result.appendLine("REQUIREMENT:")
            result.appendLine("  Type: ${requirement.entityType}")
            requirement.props["summary"]?.let { result.appendLine("  Summary: $it") }
            requirement.props["title"]?.let { result.appendLine("  Title: $it") }
            result.appendLine()

            // 2. Find implementing commits
            val commits = runBlocking {
                graphService.getRelated(
                    clientId = clientId,
                    nodeKey = requirementKey,
                    edgeTypes = listOf("fixes", "implements", "addresses"),
                    direction = Direction.INBOUND,
                    limit = 20,
                )
            }.filter { it.entityType == "commit" }

            result.appendLine("IMPLEMENTING COMMITS: ${commits.size}")
            commits.take(5).forEach { commit ->
                result.appendLine("  - ${commit.key}")
                commit.props["message"]?.let { msg ->
                    result.appendLine("    ${msg.toString().take(100)}")
                }
            }
            result.appendLine()

            // 3. Find modified code
            val modifiedCode = commits.flatMap { commit ->
                runBlocking {
                    graphService.getRelated(
                        clientId = clientId,
                        nodeKey = commit.key,
                        edgeTypes = listOf("modifies", "modifies_method", "modifies_class"),
                        direction = Direction.OUTBOUND,
                        limit = 10,
                    )
                }
            }.distinctBy { it.key }

            result.appendLine("MODIFIED CODE: ${modifiedCode.size} entities")
            modifiedCode.groupBy { it.entityType }.forEach { (type, nodes) ->
                result.appendLine("  $type: ${nodes.size}")
                nodes.take(3).forEach { node ->
                    result.appendLine("    - ${node.key}")
                }
            }
            result.appendLine()

            // 4. Find related tests
            val tests = modifiedCode.flatMap { code ->
                runBlocking {
                    graphService.getRelated(
                        clientId = clientId,
                        nodeKey = code.key,
                        edgeTypes = listOf("tests"),
                        direction = Direction.INBOUND,
                        limit = 5,
                    )
                }
            }.distinctBy { it.key }

            result.appendLine("TESTS: ${tests.size}")
            tests.take(5).forEach { test ->
                result.appendLine("  - ${test.key}")
            }
            result.appendLine()

            // 5. Find documentation
            val docs = runBlocking {
                graphService.getRelated(
                    clientId = clientId,
                    nodeKey = requirementKey,
                    edgeTypes = listOf("documents", "references"),
                    direction = Direction.INBOUND,
                    limit = 5,
                )
            }.filter { it.entityType == "confluence_page" }

            result.appendLine("DOCUMENTATION: ${docs.size}")
            docs.forEach { doc ->
                result.appendLine("  - ${doc.props["title"]}")
            }
            result.appendLine()

            result.appendLine("═════════════════════════════════════════")
            result.appendLine("TRACE COMPLETE: ${commits.size} commits → ${modifiedCode.size} code entities → ${tests.size} tests")

            return result.toString()
        } catch (e: Exception) {
            throw IllegalStateException("REQUIREMENT_TRACE_FAILED: ${e.message}", e)
        }
    }

    @Tool
    @LLMDescription("""Find who worked on specific code/feature. Returns developers, their commits, and related communication.""")
    fun findContributors(
        @LLMDescription("Entity key (e.g., 'file::src/Service.kt', 'class::UserService', 'package::com.jervis.auth')")
        entityKey: String,

        @LLMDescription("Time limit in days (e.g., 90 = last 3 months). 0 = all time.")
        daysBack: Int = 90,
    ): String {
        try {
            val clientId = plan.clientDocument.id.toHexString()

            val result = StringBuilder()
            result.appendLine("CONTRIBUTORS_ANALYSIS: $entityKey")
            result.appendLine("Time range: ${if (daysBack > 0) "last $daysBack days" else "all time"}")
            result.appendLine("═════════════════════════════════════════")
            result.appendLine()

            // 1. Find commits that modified this entity
            val commits = runBlocking {
                graphService.getRelated(
                    clientId = clientId,
                    nodeKey = entityKey,
                    edgeTypes = listOf("modifies", "modifies_method", "modifies_class", "creates"),
                    direction = Direction.INBOUND,
                    limit = 100,
                )
            }.filter { it.entityType == "commit" }

            // 2. Extract authors
            val authorCommits = commits.groupBy { it.props["authorEmail"] as? String ?: "unknown" }

            result.appendLine("CONTRIBUTORS: ${authorCommits.size}")
            authorCommits.entries.sortedByDescending { it.value.size }.forEach { (email, userCommits) ->
                result.appendLine("  ${userCommits.first().props["authorName"]} <$email>")
                result.appendLine("    Commits: ${userCommits.size}")
                result.appendLine("    Latest: ${userCommits.maxOfOrNull { it.props["timestamp"] as? String ?: "" }}")
            }
            result.appendLine()

            return result.toString()
        } catch (e: Exception) {
            throw IllegalStateException("FIND_CONTRIBUTORS_FAILED: ${e.message}", e)
        }
    }
}
