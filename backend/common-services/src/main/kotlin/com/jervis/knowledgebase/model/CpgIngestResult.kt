package com.jervis.knowledgebase.model

import kotlinx.serialization.Serializable

/**
 * Result of Joern CPG deep analysis ingest.
 *
 * Reports the number of semantic edges created in ArangoDB
 * from the Joern CPG export.
 */
@Serializable
data class CpgIngestResult(
    val status: String,
    val methodsEnriched: Int = 0,
    val extendsEdges: Int = 0,
    val callsEdges: Int = 0,
    val usesTypeEdges: Int = 0,
)
