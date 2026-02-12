package com.jervis.knowledgebase.model

import kotlinx.serialization.Serializable

/**
 * Request to run Joern CPG deep analysis and import semantic edges.
 *
 * Dispatches a Joern K8s Job to generate a Code Property Graph (CPG),
 * then imports pruned edges (calls, extends, uses_type) into ArangoDB.
 *
 * Requires that tree-sitter structural ingest has already run
 * (method and class nodes must exist in the graph).
 */
@Serializable
data class CpgIngestRequest(
    val clientId: String,
    val projectId: String,
    val branch: String,
    val workspacePath: String,  // Absolute path to project directory on PVC
)
