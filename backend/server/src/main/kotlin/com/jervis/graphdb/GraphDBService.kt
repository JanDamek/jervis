package com.jervis.graphdb

import com.arangodb.entity.CollectionType
import com.arangodb.model.CollectionCreateOptions
import com.arangodb.model.PersistentIndexOptions
import com.jervis.graphdb.model.Direction
import com.jervis.graphdb.model.GraphEdge
import com.jervis.graphdb.model.GraphEdgeResult
import com.jervis.graphdb.model.GraphNode
import com.jervis.graphdb.model.GraphNodeResult
import com.jervis.graphdb.model.GraphSchemaStatus
import com.jervis.graphdb.model.TraversalSpec
import com.jervis.types.ClientId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import org.springframework.stereotype.Service

@Service
class GraphDBService(
    private val connector: ArangoConnector,
) {
    private val logger = KotlinLogging.logger {}

    suspend fun upsertNode(
        clientId: ClientId,
        node: GraphNode,
    ): GraphNodeResult {
        val collectionName = nodeCollection(clientId)
        val key = normalizeKey(node.key)
        return withContext(Dispatchers.IO) {
            runCatching {
                val db = connector.ensureDatabase()
                val collection = db.collection(collectionName)
                if (!collection.exists()) {
                    db.createCollection(collectionName)
                }

                val existed = runCatching { collection.documentExists(key) }.getOrDefault(false)
                val mergedRagChunks: List<String> =
                    if (existed) {
                        val existing: Map<*, *>? =
                            runCatching { collection.getDocument(key, Map::class.java) as Map<*, *> }.getOrNull()
                        val current =
                            (existing?.get("ragChunks") as? List<*>)?.mapNotNull { it?.toString() } ?: emptyList()
                        (current + node.ragChunks).toSet().toList()
                    } else {
                        node.ragChunks
                    }

                val doc =
                    HashMap<String, Any?>().apply {
                        put("_key", key)
                        put("type", node.entityType)
                        if (mergedRagChunks.isNotEmpty()) put("ragChunks", mergedRagChunks)
                    }

                if (existed) collection.updateDocument(key, doc) else collection.insertDocument(doc)

                GraphNodeResult(ok = true, key = key, created = !existed)
            }.getOrElse { e ->
                logger.error(e) { "Graph upsertNode failed for clientId=$clientId, node=${node.key}" }
                GraphNodeResult(ok = false, key = node.key, created = false, warnings = listOf(e.message ?: "error"))
            }
        }
    }

    suspend fun upsertEdge(
        clientId: ClientId,
        edge: GraphEdge,
    ): GraphEdgeResult {
        val edgeCollection = edgeCollection(clientId)
        val fromKey = edge.fromKey
        val toKey = edge.toKey
        return withContext(Dispatchers.IO) {
            runCatching {
                val db = connector.ensureDatabase()
                val coll = db.collection(edgeCollection)
                if (!coll.exists()) {
                    db.createCollection(edgeCollection, CollectionCreateOptions().type(CollectionType.EDGES))
                }

                val edgeDoc =
                    HashMap<String, Any?>().apply {
                        put("_key", normalizeKey(edge.edgeType + "::" + fromKey + "->" + toKey))
                        put("edgeType", edge.edgeType)
                        put("_from", keyToDocumentId(clientId, fromKey))
                        put("_to", keyToDocumentId(clientId, toKey))
                    }
                val key = edgeDoc["_key"] as String
                val existed = runCatching { coll.documentExists(key) }.getOrDefault(false)
                if (existed) coll.updateDocument(key, edgeDoc) else coll.insertDocument(edgeDoc)

                GraphEdgeResult(
                    ok = true,
                    edgeType = edge.edgeType,
                    fromKey = fromKey,
                    toKey = toKey,
                    created = !existed,
                )
            }.getOrElse { e ->
                logger.error(e) { "Graph upsertEdge failed for clientId=$clientId, edge=${edge.edgeType}" }
                GraphEdgeResult(
                    ok = false,
                    edgeType = edge.edgeType,
                    fromKey = edge.fromKey,
                    toKey = edge.toKey,
                    created = false,
                    warnings = listOf(e.message ?: "error"),
                )
            }
        }
    }

    suspend fun getRelated(
        clientId: ClientId,
        nodeKey: String,
        edgeTypes: List<String>,
        direction: Direction,
        limit: Int,
    ): List<GraphNode> {
        // TODO: implement AQL traversal once driver signature is verified
        logger.debug { "getRelated requested: nodeKey=$nodeKey, edgeTypes=$edgeTypes, direction=$direction, limit=$limit" }
        return emptyList()
    }

    suspend fun traverse(
        clientId: ClientId,
        startKey: String,
        spec: TraversalSpec,
    ): Flow<GraphNode> {
        // TODO: implement AQL traversal once driver signature is verified
        logger.debug { "traverse requested: startKey=$startKey, spec=$spec" }
        return emptyFlow()
    }

    suspend fun ensureSchema(clientId: ClientId): GraphSchemaStatus =
        withContext(Dispatchers.IO) {
            val createdCollections = mutableListOf<String>()
            val createdIndexes = mutableListOf<String>()
            val warnings = mutableListOf<String>()
            var createdGraph = false

            runCatching {
                val db = connector.ensureDatabase()

                val nodeCollName = nodeCollection(clientId)
                val edgeCollName = edgeCollection(clientId)
                val graphName = graphName(clientId)

                // Create node collection
                val nodeColl = db.collection(nodeCollName)
                if (!nodeColl.exists()) {
                    db.createCollection(nodeCollName)
                    createdCollections += nodeCollName
                }
                runCatching { nodeColl.ensurePersistentIndex(listOf("type"), PersistentIndexOptions()) }
                    .onSuccess { createdIndexes += "$nodeCollName:persistent(type)" }
                    .onFailure { warnings += it.message ?: "index err" }

                // Create edge collection
                val edgeColl = db.collection(edgeCollName)
                if (!edgeColl.exists()) {
                    db.createCollection(edgeCollName, CollectionCreateOptions().type(CollectionType.EDGES))
                    createdCollections += edgeCollName
                }
                runCatching { edgeColl.ensurePersistentIndex(listOf("edgeType"), PersistentIndexOptions()) }
                    .onSuccess { createdIndexes += "$edgeCollName:persistent(edgeType)" }
                    .onFailure { warnings += it.message ?: "index err" }

                // Create named graph (if doesn't exist)
                runCatching {
                    val graph = db.graph(graphName)
                    if (!graph.exists()) {
                        val edgeDefinition =
                            com.arangodb.entity.EdgeDefinition()
                                .collection(edgeCollName)
                                .from(nodeCollName)
                                .to(nodeCollName)
                        db.createGraph(graphName, listOf(edgeDefinition))
                        createdGraph = true
                        logger.info { "Created named graph: $graphName" }
                    }
                }.onFailure { e ->
                    warnings += "Failed to create graph: ${e.message}"
                    logger.warn(e) { "Failed to create graph $graphName" }
                }
            }.onFailure { e ->
                warnings += e.message ?: "unknown error"
                logger.error(e) { "ensureSchema failed for clientId=$clientId" }
            }

            GraphSchemaStatus(
                ok = warnings.isEmpty(),
                createdCollections = createdCollections,
                createdIndexes = createdIndexes,
                createdGraph = createdGraph,
                warnings = warnings,
            )
        }

    private fun keyToDocumentId(
        clientId: ClientId,
        key: String,
    ): String {
        val collection = nodeCollection(clientId)
        val normalizedKey = normalizeKey(key)
        return "$collection/$normalizedKey"
    }

    private fun nodeCollection(clientId: ClientId): String = "${collPrefix(clientId)}_nodes"

    private fun edgeCollection(clientId: ClientId): String = "${collPrefix(clientId)}_edges"

    private fun graphName(clientId: ClientId): String = "${collPrefix(clientId)}_graph"

    private fun normalizeKey(key: String): String =
        key
            .replace("/", "_")
            .replace(" ", "_")
            .replace(Regex("[^A-Za-z0-9_:\\.-]"), "_")

    private fun collPrefix(clientId: ClientId): String = "c$clientId"
}
