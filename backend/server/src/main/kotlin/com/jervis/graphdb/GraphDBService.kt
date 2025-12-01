package com.jervis.graphdb

import com.jervis.graphdb.model.*
import kotlinx.coroutines.flow.Flow

/**
 * Primary API for working with per-client Knowledge Graph in ArangoDB.
 */
interface GraphDBService {
    suspend fun upsertNode(clientId: String, node: GraphNode): GraphNodeResult

    suspend fun upsertEdge(clientId: String, edge: GraphEdge): GraphEdgeResult

    suspend fun getRelated(
        clientId: String,
        nodeKey: String,
        edgeTypes: List<String>,
        direction: Direction,
        limit: Int = 50,
    ): List<GraphNode>

    suspend fun traverse(
        clientId: String,
        startKey: String,
        spec: TraversalSpec,
    ): Flow<GraphNode>

    suspend fun ensureSchema(clientId: String): GraphSchemaStatus

    suspend fun health(clientId: String): GraphHealth
}
