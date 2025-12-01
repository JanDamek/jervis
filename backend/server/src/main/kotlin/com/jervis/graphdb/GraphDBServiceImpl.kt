package com.jervis.graphdb

import com.arangodb.entity.CollectionType
import com.arangodb.model.CollectionCreateOptions
import com.arangodb.model.FulltextIndexOptions
import com.arangodb.model.PersistentIndexOptions
import com.jervis.graphdb.model.Direction
import com.jervis.graphdb.model.GraphEdge
import com.jervis.graphdb.model.GraphEdgeResult
import com.jervis.graphdb.model.GraphHealth
import com.jervis.graphdb.model.GraphNode
import com.jervis.graphdb.model.GraphNodeResult
import com.jervis.graphdb.model.GraphSchemaStatus
import com.jervis.graphdb.model.TraversalSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import org.springframework.stereotype.Service

@Service
class GraphDBServiceImpl(
    private val connector: ArangoConnector,
) : GraphDBService {
    private val logger = KotlinLogging.logger {}

    override suspend fun upsertNode(clientId: String, node: GraphNode): GraphNodeResult {
        val (collectionName, key) = resolveCollectionAndKey(clientId, node)
        return withContext(Dispatchers.IO) {
            runCatching {
                val db = connector.ensureDatabase()
                val collection = db.collection(collectionName)
                if (!collection.exists()) {
                    db.createCollection(collectionName)
                }

                val existed = runCatching { collection.documentExists(key) }.getOrDefault(false)
                val mergedRagChunks: List<String> = if (existed) {
                    val existing: Map<*, *>? = runCatching { collection.getDocument(key, Map::class.java) as Map<*, *> }.getOrNull()
                    val current = (existing?.get("ragChunks") as? List<*>)?.mapNotNull { it?.toString() } ?: emptyList()
                    (current + node.ragChunks).toSet().toList()
                } else node.ragChunks

                val doc = HashMap<String, Any?>().apply {
                    put("_key", key)
                    put("entityType", node.entityType)
                    putAll(node.props)
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

    override suspend fun upsertEdge(clientId: String, edge: GraphEdge): GraphEdgeResult {
        val edgeCollection = collPrefix(clientId) + "_" + edge.edgeType
        val fromKey = edge.fromKey
        val toKey = edge.toKey
        return withContext(Dispatchers.IO) {
            runCatching {
                val db = connector.ensureDatabase()
                val coll = db.collection(edgeCollection)
                if (!coll.exists()) {
                    db.createCollection(edgeCollection, CollectionCreateOptions().type(CollectionType.EDGES))
                }

                val edgeDoc = HashMap<String, Any?>().apply {
                    put("_key", normalizeKey(edge.edgeType + "::" + fromKey + "->" + toKey))
                    put("_from", keyToDocumentId(clientId, fromKey))
                    put("_to", keyToDocumentId(clientId, toKey))
                    putAll(edge.props)
                }
                val key = edgeDoc["_key"] as String
                val existed = runCatching { coll.documentExists(key) }.getOrDefault(false)
                if (existed) coll.updateDocument(key, edgeDoc) else coll.insertDocument(edgeDoc)

                GraphEdgeResult(ok = true, edgeType = edge.edgeType, fromKey = fromKey, toKey = toKey, created = !existed)
            }.getOrElse { e ->
                logger.error(e) { "Graph upsertEdge failed for clientId=$clientId, edge=${edge.edgeType}" }
                GraphEdgeResult(ok = false, edgeType = edge.edgeType, fromKey = edge.fromKey, toKey = edge.toKey, created = false, warnings = listOf(e.message ?: "error"))
            }
        }
    }

    override suspend fun getRelated(
        clientId: String,
        nodeKey: String,
        edgeTypes: List<String>,
        direction: Direction,
        limit: Int,
    ): List<GraphNode> {
        // TODO: implement AQL traversal once driver signature is verified
        logger.debug { "getRelated requested: nodeKey=$nodeKey, edgeTypes=$edgeTypes, direction=$direction, limit=$limit" }
        return emptyList()
    }

    override suspend fun traverse(
        clientId: String,
        startKey: String,
        spec: TraversalSpec,
    ): Flow<GraphNode> {
        // TODO: implement AQL traversal once driver signature is verified
        logger.debug { "traverse requested: startKey=$startKey, spec=$spec" }
        return emptyFlow()
    }

    override suspend fun ensureSchema(clientId: String): GraphSchemaStatus = withContext(Dispatchers.IO) {
        val createdCollections = mutableListOf<String>()
        val createdIndexes = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        var createdGraph = false

        runCatching {
            val db = connector.ensureDatabase()

            val nodeCollections = nodeCollections(clientId)
            val edgeCollections = edgeCollections(clientId)

            // Create node collections + indexes
            nodeCollections.forEach { name ->
                val coll = db.collection(name)
                if (!coll.exists()) {
                    db.createCollection(name)
                    createdCollections += name
                }
                // Persistent indexes
                runCatching { coll.ensurePersistentIndex(listOf("entityType"), PersistentIndexOptions()) }
                    .onSuccess { createdIndexes += "$name:persistent(entityType)" }
                    .onFailure { warnings += it.message ?: "index err" }
                runCatching { coll.ensurePersistentIndex(listOf("projectId"), PersistentIndexOptions()) }
                    .onSuccess { createdIndexes += "$name:persistent(projectId)" }
                    .onFailure { warnings += it.message ?: "index err" }
                // Fulltext indexes (best effort)
                runCatching { coll.ensureFulltextIndex(listOf("title"), FulltextIndexOptions().minLength(3)) }
                    .onSuccess { createdIndexes += "$name:fulltext(title)" }
                    .onFailure { warnings += it.message ?: "index err" }
                runCatching { coll.ensureFulltextIndex(listOf("summary"), FulltextIndexOptions().minLength(3)) }
                    .onSuccess { createdIndexes += "$name:fulltext(summary)" }
                    .onFailure { warnings += it.message ?: "index err" }
                runCatching { coll.ensureFulltextIndex(listOf("contentHash"), FulltextIndexOptions().minLength(3)) }
                    .onSuccess { createdIndexes += "$name:fulltext(contentHash)" }
                    .onFailure { warnings += it.message ?: "index err" }
            }

            // Create edge collections
            edgeCollections.forEach { name ->
                val coll = db.collection(name)
                if (!coll.exists()) {
                    db.createCollection(name, CollectionCreateOptions().type(CollectionType.EDGES))
                    createdCollections += name
                }
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

    override suspend fun health(clientId: String): GraphHealth {
        val dbHealth = connector.health()
        return if (dbHealth.isSuccess) GraphHealth(ok = true) else GraphHealth(ok = false, details = dbHealth.exceptionOrNull()?.message)
    }

    // Helpers
    private fun resolveCollectionAndKey(clientId: String, node: GraphNode): Pair<String, String> {
        val type = node.entityType.lowercase()
        val cp = collPrefix(clientId)
        val collection = when (type) {
            "document" -> "${cp}_documents"
            "entity" -> "${cp}_entities"
            "file" -> "${cp}_files"
            "class" -> "${cp}_classes"
            "method" -> "${cp}_methods"
            "ticket" -> "${cp}_tickets"
            "commit" -> "${cp}_commits"
            "email" -> "${cp}_emails"
            "slack" -> "${cp}_slack"
            "requirement" -> "${cp}_requirements"
            else -> "${cp}_entities"
        }
        val key = normalizeKey(node.key)
        return collection to key
    }

    private fun keyToDocumentId(clientId: String, key: String): String {
        val prefix = key.substringBefore("::", "entity")
        val cp = collPrefix(clientId)
        val collection = when (prefix) {
            "document" -> "${cp}_documents"
            "entity" -> "${cp}_entities"
            "file" -> "${cp}_files"
            "class" -> "${cp}_classes"
            "method" -> "${cp}_methods"
            "ticket" -> "${cp}_tickets"
            "commit" -> "${cp}_commits"
            "email" -> "${cp}_emails"
            "slack" -> "${cp}_slack"
            "requirement" -> "${cp}_requirements"
            else -> "${cp}_entities"
        }
        val actualKey = if (key.contains("::")) key.substringAfter("::") else key
        return "$collection/$actualKey"
    }

    private fun nodeCollections(clientId: String): List<String> = listOf(
        "${collPrefix(clientId)}_documents",
        "${collPrefix(clientId)}_entities",
        "${collPrefix(clientId)}_files",
        "${collPrefix(clientId)}_classes",
        "${collPrefix(clientId)}_methods",
        "${collPrefix(clientId)}_tickets",
        "${collPrefix(clientId)}_commits",
        "${collPrefix(clientId)}_emails",
        "${collPrefix(clientId)}_slack",
        "${collPrefix(clientId)}_requirements",
    )

    private fun edgeCollections(clientId: String): List<String> = listOf(
        "${collPrefix(clientId)}_mentions",
        "${collPrefix(clientId)}_defines",
        "${collPrefix(clientId)}_implements",
        "${collPrefix(clientId)}_modified_by",
        "${collPrefix(clientId)}_changes_ticket",
        "${collPrefix(clientId)}_affects",
        "${collPrefix(clientId)}_owned_by",
        "${collPrefix(clientId)}_concerns",
        "${collPrefix(clientId)}_describes",
    )

    private fun normalizeKey(key: String): String =
        key.replace("/", "_")
            .replace(" ", "_")
            .replace(Regex("[^A-Za-z0-9_:\\.-]"), "_")

    private fun collPrefix(clientId: String): String = "c$clientId"
}
