package com.jervis.rag.internal.graphdb.model

data class GraphNode(
    val key: String,
    val entityType: String,
    val ragChunks: List<String> = emptyList(),
)

data class GraphEdge(
    val edgeType: String,
    val fromKey: String,
    val toKey: String,
)

enum class Direction { INBOUND, OUTBOUND, ANY }

data class TraversalSpec(
    val maxDepth: Int = 3,
    val edgeTypes: List<String> = emptyList(),
)

data class GraphNodeResult(
    val ok: Boolean,
    val key: String,
    val created: Boolean,
    val warnings: List<String> = emptyList(),
)

data class GraphEdgeResult(
    val ok: Boolean,
    val edgeType: String,
    val fromKey: String,
    val toKey: String,
    val created: Boolean,
    val warnings: List<String> = emptyList(),
)

data class GraphSchemaStatus(
    val ok: Boolean,
    val createdCollections: List<String> = emptyList(),
    val createdIndexes: List<String> = emptyList(),
    val createdGraph: Boolean = false,
    val warnings: List<String> = emptyList(),
)
