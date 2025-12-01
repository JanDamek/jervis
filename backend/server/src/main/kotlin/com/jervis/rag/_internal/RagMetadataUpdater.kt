package com.jervis.rag._internal

import com.jervis.graphdb.GraphDBService
import com.jervis.graphdb.model.GraphNode
import com.jervis.rag._internal.model.RagMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import org.bson.types.ObjectId
import org.springframework.stereotype.Component

/**
 * Updates cross-links between RAG (Weaviate) and Graph (Arango).
 *
 * On chunk store, appends ragChunkId to referenced Graph nodes.
 * NOTE: Minimal skeleton – merging existing ragChunks should read node; current upsert may overwrite.
 */
@Component
class RagMetadataUpdater(
    private val graphDBService: GraphDBService,
) {
    private val logger = KotlinLogging.logger {}

    suspend fun onChunkStored(clientId: ObjectId, ragChunkId: String, meta: RagMetadata) = withContext(Dispatchers.IO) {
        if (meta.graphRefs.isEmpty()) return@withContext

        meta.graphRefs.forEach { ref ->
            try {
                val (entityType, key) = parseGraphRef(ref)
                val node = GraphNode(
                    key = key,
                    entityType = entityType,
                    props = emptyMap(),
                    ragChunks = listOf(ragChunkId), // TODO: merge with existing list
                )
                val result = graphDBService.upsertNode(clientId.toHexString(), node)
                if (!result.ok) {
                    logger.warn { "Failed to link ragChunk=$ragChunkId to node=$ref: ${result.warnings}" }
                }
            } catch (e: Exception) {
                logger.warn(e) { "Failed processing graphRef='$ref' for ragChunk=$ragChunkId" }
            }
        }
    }

    private fun parseGraphRef(ref: String): Pair<String, String> {
        // Accept formats like "entity/123" or "class::com.Foo"
        return if (ref.contains('/')) {
            val type = ref.substringBefore('/')
            val key = ref.substringAfter('/')
            type to key
        } else if (ref.contains("::")) {
            val type = ref.substringBefore("::")
            type to ref
        } else {
            "entity" to ref
        }
    }
}
