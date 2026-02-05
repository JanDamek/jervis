package com.jervis.knowledgebase.service.graphdb.model

data class TraversalSpec(
    val maxDepth: Int = 1,
    val edgeTypes: List<String>? = null,
    val direction: Direction = Direction.OUTBOUND,
)
