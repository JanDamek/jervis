package com.jervis.koog.tools

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import com.jervis.entity.PendingTaskDocument
import com.jervis.graphdb.GraphDBService
import com.jervis.graphdb.model.GraphEdge
import com.jervis.graphdb.model.GraphNode
import com.jervis.koog.qualifier.types.BaseDocResult
import com.jervis.koog.qualifier.types.StoreKnowledgeResult
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
    ): StoreKnowledgeResult {
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

            return StoreKnowledgeResult(
                success = true,
                chunkId = chunkId,
                mainNodeKey = mainNodeKey,
                nodesCreated = nodesToCreate.size,
                edgesCreated = edgesToCreate.size,
                errorMessage = null
            )
        } catch (e: Exception) {
            logger.error(e) { "Failed to store knowledge: ${e.message}" }
            return StoreKnowledgeResult(
                success = false,
                chunkId = "",
                mainNodeKey = mainNodeKey,
                nodesCreated = 0,
                edgesCreated = 0,
                errorMessage = e.message ?: "Unknown error"
            )
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

    @Tool
    @LLMDescription(
        "Creates a base document node that represents the entire document. Chunks will be linked to this base node via PART_OF edges.",
    )
    suspend fun createBaseDocument(
        @LLMDescription("The node key for the base document (e.g., `doc_literature_screening`).")
        baseNodeKey: String,
        @LLMDescription("High-level description of the entire document (1-3 sentences).")
        baseInfo: String,
    ): BaseDocResult {
        try {
            // Store baseInfo as RAG chunk
            val chunkId = knowledgeService.storeChunk(
                StoreChunkRequest(
                    clientId = pendingTaskContext.clientId,
                    projectId = pendingTaskContext.projectId,
                    content = baseInfo,
                    graphRefs = listOf(baseNodeKey),
                    sourceUrn = pendingTaskContext.sourceUrn,
                ),
            )

            // Create base document node
            graphDBService.upsertNode(
                clientId = pendingTaskContext.clientId,
                node = GraphNode(
                    key = sanitizeNodeKey(baseNodeKey),
                    entityType = "document",
                    ragChunks = listOf(chunkId),
                ),
            )

            logger.info {
                "BASE_DOCUMENT_CREATED: nodeKey=$baseNodeKey, chunkId=$chunkId"
            }

            return BaseDocResult(
                success = true,
                chunkId = chunkId,
                nodeKey = baseNodeKey,
                errorMessage = null
            )
        } catch (e: Exception) {
            logger.error(e) { "Failed to create base document: ${e.message}" }
            return BaseDocResult(
                success = false,
                chunkId = "",
                nodeKey = baseNodeKey,
                errorMessage = e.message ?: "Unknown error"
            )
        }
    }

    private fun parseTriplet(triplet: String): Triple<String, String, String>? {
        val regex = """^(.+?)\s*-\[(.+?)\]->\s*(.+)$""".toRegex()
        val match = regex.matchEntire(triplet.trim()) ?: return null

        val (source, edge, target) = match.destructured
        return Triple(source.trim(), edge.trim(), target.trim())
    }
}
