package com.jervis.knowledgebase.service.graphdb.model

data class GraphNode(
    val key: String,
    val entityType: String,
    val ragChunks: List<String> = emptyList(),
    val properties: Map<String, Any> = emptyMap(),
)
