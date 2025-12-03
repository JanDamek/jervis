package com.jervis.koog.tools

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import com.jervis.domain.plan.Plan
import com.jervis.graphdb.GraphDBService
import com.jervis.graphdb.model.GraphEdge
import com.jervis.graphdb.model.GraphNode
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging

/**
 * GraphRagLinkerTool - Bi-directional linking between Graph and RAG.
 *
 * NEW ARCHITECTURE (Graph-Based Routing):
 * - Used by KoogQualifierAgent (CPU) to link structured data (Graph) with searchable content (RAG)
 * - Creates Graph nodes with metadata (entities, relationships)
 * - Links RAG chunk IDs to Graph nodes for semantic search
 * - Creates Graph edges to represent relationships
 *
 * Bi-directional Linking:
 * - Graph → RAG: node.ragChunks contains list of chunk IDs from RAG
 * - RAG → Graph: chunk metadata contains nodeKey for reverse lookup
 * - This enables: semantic search (RAG) + structured navigation (Graph)
 *
 * Example Flow:
 * 1. SequentialIndexingTool creates RAG chunks → returns chunk IDs
 * 2. This tool creates Graph node with those chunk IDs in ragChunks
 * 3. Later: RAG search returns chunk → use nodeKey → explore Graph relationships
 * 4. Later: Graph traversal finds node → use ragChunks → retrieve full content from RAG
 */
@LLMDescription("Link Graph nodes with RAG chunks for bi-directional navigation between structured data and semantic search")
class GraphRagLinkerTool(
    private val plan: Plan,
    private val graphService: GraphDBService,
) : ToolSet {
    companion object {
        private val logger = KotlinLogging.logger {}
    }

    @Tool
    @LLMDescription("""Create Graph node with RAG chunk links.
Creates or updates a node in the Knowledge Graph with bi-directional links to RAG chunks.
Use this after indexing content to RAG - it enables structured navigation AND semantic search.

Node Key Format: 'type::identifier' (e.g., 'email::abc123', 'jira::PROJ-123', 'commit::abc123')

RAG Chunks: List of chunk IDs returned from SequentialIndexingTool (e.g., 'doc123:chunk:0:hash')

Properties: Structured metadata (title, author, date, status, etc.) in key=value format

This enables two query patterns:
1. Semantic search (RAG) → returns nodeKey → explore Graph relationships
2. Graph traversal → finds node → use ragChunks → retrieve full RAG content""")
    fun createNodeWithRagLinks(
        @LLMDescription("Unique node key: 'type::identifier' (e.g., 'email::msg123', 'jira::PROJ-456')")
        nodeKey: String,

        @LLMDescription("Node type: email, jira_issue, git_commit, confluence_page, file, class, method, user, etc.")
        nodeType: String,

        @LLMDescription("Node properties as key=value lines (e.g., title=..., author=..., date=..., status=...)")
        properties: String,

        @LLMDescription("RAG chunk IDs to link (comma-separated, from SequentialIndexingTool output)")
        ragChunkIds: String = "",
    ): String = runBlocking {
        try {
            val clientId = plan.clientDocument.id.toHexString()

            // Parse properties
            val props = properties.lines()
                .mapNotNull { line ->
                    val idx = line.indexOf('=')
                    if (idx <= 0) null else line.substring(0, idx).trim() to line.substring(idx + 1).trim()
                }
                .toMap()

            // Parse RAG chunk IDs
            val ragChunks = if (ragChunkIds.isBlank()) {
                emptyList()
            } else {
                ragChunkIds.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            }

            logger.info { "GRAPH_RAG_LINK: nodeKey='$nodeKey', type='$nodeType', ragChunks=${ragChunks.size}" }

            // Create/update node with RAG links
            val node = GraphNode(
                key = nodeKey,
                entityType = nodeType,
                props = props,
                ragChunks = ragChunks,
            )

            val result = graphService.upsertNode(clientId, node)

            buildString {
                appendLine("✓ Graph node ${if (result.created) "created" else "updated"} with RAG links")
                appendLine("  Node Key: $nodeKey")
                appendLine("  Node Type: $nodeType")
                appendLine("  Properties: ${props.size}")
                appendLine("  RAG Chunks: ${ragChunks.size}")
                if (result.warnings.isNotEmpty()) {
                    appendLine("  Warnings: ${result.warnings.joinToString(", ")}")
                }
                appendLine()
                appendLine("Bi-directional linking complete:")
                appendLine("  Graph → RAG: Node contains ${ragChunks.size} chunk IDs")
                appendLine("  RAG → Graph: Each chunk links back to node '$nodeKey'")
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to create node with RAG links: $nodeKey" }
            throw IllegalStateException("GRAPH_RAG_LINK_FAILED: ${e.message}", e)
        }
    }

    @Tool
    @LLMDescription("""Create relationship between two Graph nodes.
Links two entities in the Knowledge Graph with a typed edge.
Use to represent relationships: mentions, defines, implements, modifies, fixes, blocks, relates_to, etc.

Common Edge Types:
- CODE: calls, defines, implements, extends, imports
- VCS: modifies, creates, deletes, authored_by
- ISSUES: fixes, blocks, relates_to, assigned_to, discusses
- DOCS: documents, describes, references
- COMMUNICATION: mentions, replies_to, forwards

Edge properties can include: confidence, timestamp, description, weight""")
    fun createRelationship(
        @LLMDescription("Source node key")
        fromKey: String,

        @LLMDescription("Target node key")
        toKey: String,

        @LLMDescription("Edge type: mentions, defines, implements, modifies, fixes, blocks, relates_to, documents, etc.")
        edgeType: String,

        @LLMDescription("Optional edge properties as key=value lines (e.g., confidence=0.9, timestamp=2025-01-15)")
        properties: String = "",
    ): String = runBlocking {
        try {
            val clientId = plan.clientDocument.id.toHexString()

            // Parse edge properties
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

            logger.info { "GRAPH_RELATIONSHIP: $fromKey --[$edgeType]--> $toKey" }

            val edge = GraphEdge(
                edgeType = edgeType,
                fromKey = fromKey,
                toKey = toKey,
                props = props,
            )

            val result = graphService.upsertEdge(clientId, edge)

            buildString {
                appendLine("✓ Relationship ${if (result.created) "created" else "updated"}")
                appendLine("  From: $fromKey")
                appendLine("  To: $toKey")
                appendLine("  Type: $edgeType")
                if (props.isNotEmpty()) {
                    appendLine("  Properties: ${props.size}")
                    props.forEach { (k, v) ->
                        appendLine("    $k: $v")
                    }
                }
                if (result.warnings.isNotEmpty()) {
                    appendLine("  Warnings: ${result.warnings.joinToString(", ")}")
                }
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to create relationship: $fromKey -> $toKey" }
            throw IllegalStateException("GRAPH_RELATIONSHIP_FAILED: ${e.message}", e)
        }
    }

    @Tool
    @LLMDescription("""Update RAG chunk links for existing node.
Adds or replaces RAG chunk IDs for an existing Graph node.
Use when re-indexing content or adding more chunks to existing node.

Mode:
- APPEND: Add new chunks to existing list (default)
- REPLACE: Replace all existing chunks with new list""")
    fun updateRagLinks(
        @LLMDescription("Node key to update")
        nodeKey: String,

        @LLMDescription("RAG chunk IDs (comma-separated)")
        ragChunkIds: String,

        @LLMDescription("Update mode: APPEND or REPLACE")
        mode: String = "APPEND",
    ): String = runBlocking {
        try {
            val clientId = plan.clientDocument.id.toHexString()

            // Parse new chunk IDs
            val newChunks = ragChunkIds.split(",").map { it.trim() }.filter { it.isNotEmpty() }

            logger.info { "GRAPH_RAG_UPDATE: nodeKey='$nodeKey', mode='$mode', newChunks=${newChunks.size}" }

            // Get existing node
            val existingNodes = graphService.getRelated(
                clientId = clientId,
                nodeKey = nodeKey,
                edgeTypes = emptyList(),
                direction = com.jervis.graphdb.model.Direction.ANY,
                limit = 1,
            )

            if (existingNodes.isEmpty()) {
                throw IllegalStateException("Node not found: $nodeKey")
            }

            val existingNode = existingNodes.first()

            // Determine final chunk list based on mode
            val finalChunks = when (mode.uppercase()) {
                "APPEND" -> (existingNode.ragChunks + newChunks).distinct()
                "REPLACE" -> newChunks
                else -> throw IllegalArgumentException("Invalid mode: $mode. Use APPEND or REPLACE")
            }

            // Update node with new chunk list
            val updatedNode = GraphNode(
                key = existingNode.key,
                entityType = existingNode.entityType,
                props = existingNode.props,
                ragChunks = finalChunks,
            )

            graphService.upsertNode(clientId, updatedNode)

            buildString {
                appendLine("✓ RAG links updated for node '$nodeKey'")
                appendLine("  Mode: $mode")
                appendLine("  Previous chunks: ${existingNode.ragChunks.size}")
                appendLine("  New chunks: ${newChunks.size}")
                appendLine("  Final total: ${finalChunks.size}")
            }
        } catch (e: IllegalArgumentException) {
            throw e
        } catch (e: IllegalStateException) {
            throw e
        } catch (e: Exception) {
            logger.error(e) { "Failed to update RAG links: $nodeKey" }
            throw IllegalStateException("GRAPH_RAG_UPDATE_FAILED: ${e.message}", e)
        }
    }
}
