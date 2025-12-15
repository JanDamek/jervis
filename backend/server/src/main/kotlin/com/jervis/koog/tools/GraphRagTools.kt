package com.jervis.koog.tools

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import com.jervis.entity.PendingTaskDocument
import com.jervis.graphdb.GraphDBService
import com.jervis.graphdb.model.GraphEdge
import com.jervis.graphdb.model.GraphNode
import com.jervis.rag.KnowledgeService
import com.jervis.rag.StoreChunkRequest
import mu.KotlinLogging

@LLMDescription("Knowledge Graph operations for store RAG and Graph.")
class GraphRagTools(
    private val graphDBService: GraphDBService,
    private val knowledgeService: KnowledgeService,
    private val pendingTaskContext: PendingTaskDocument,
) : ToolSet {
    private val logger = KotlinLogging.logger {}

    @Tool
    @LLMDescription(
        "Stores a knowledge chunk and its graph representation. This is the primary tool for saving structured data.",
    )
    suspend fun storeKnowledge(
        @LLMDescription("A high-quality, human-readable summary of the information being stored.")
        content: String,
        @LLMDescription("A list of graph relationships in 'source_node -[RELATIONSHIP]-> target_node' format.")
        graphStructure: List<String>,
        @LLMDescription("The primary subject node key of this chunk (e.g., `page::12345`). This is critical for linking summaries.")
        mainNodeKey: String,
    ): String {
        try {
            val parsedTriplets = graphStructure.mapNotNull { parseTriplet(it) }
            val nodesToCreate = mutableSetOf<String>()
            val edgesToCreate = mutableListOf<Triple<String, String, String>>()

            for ((sourceKey, edgeType, targetKey) in parsedTriplets) {
                nodesToCreate.add(sourceKey)
                nodesToCreate.add(targetKey)
                edgesToCreate.add(Triple(sourceKey, edgeType, targetKey))
            }
            nodesToCreate.add(mainNodeKey)

            val chunkId =
                knowledgeService.storeChunk(
                    StoreChunkRequest(
                        clientId = pendingTaskContext.clientId,
                        projectId = pendingTaskContext.projectId,
                        content = content,
                        graphRefs = nodesToCreate.toList(),
                        sourceUrn = pendingTaskContext.sourceUrn,
                    ),
                )

            for (nodeKey in nodesToCreate) {
                graphDBService.upsertNode(
                    clientId = pendingTaskContext.clientId,
                    node =
                        GraphNode(
                            key = sanitizeNodeKey(nodeKey),
                            entityType = parseNodeKey(nodeKey).first,
                            ragChunks = listOf(chunkId),
                        ),
                )
            }

            for ((sourceKey, edgeType, targetKey) in edgesToCreate) {
                graphDBService.upsertEdge(
                    clientId = pendingTaskContext.clientId,
                    edge =
                        GraphEdge(
                            edgeType = edgeType,
                            fromKey = sanitizeNodeKey(sourceKey),
                            toKey = sanitizeNodeKey(targetKey),
                        ),
                )
            }

            logger.info {
                "KNOWLEDGE_STORED: chunkId=$chunkId, mainNode=$mainNodeKey, nodes=${nodesToCreate.size}, edges=${edgesToCreate.size}"
            }

            return """✓ Knowledge stored successfully. ChunkId: $chunkId, MainNode: $mainNodeKey, Nodes: ${nodesToCreate.size}, Edges: ${edgesToCreate.size}"""
        } catch (e: Exception) {
            logger.error(e) { "Failed to store knowledge: ${e.message}" }
            return """✗ Failed to store knowledge: ${e.message}"""
        }
    }

    private fun sanitizeNodeKey(key: String): String = key.replace("::", "_")

    private fun parseNodeKey(key: String): Pair<String, String> {
        val parts = key.split("::", limit = 2)
        return if (parts.size == 2) {
            parts[0] to parts[1]
        } else {
            "entity" to key
        }
    }

    private fun parseTriplet(triplet: String): Triple<String, String, String>? {
        val regex = """^(.+?)\s*-\[(.+?)\]->\s*(.+)$""".toRegex()
        val match = regex.matchEntire(triplet.trim()) ?: return null

        val (source, edge, target) = match.destructured
        return Triple(source.trim(), edge.trim(), target.trim())
    }
}
