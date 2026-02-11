package com.jervis.knowledgebase.model

import kotlinx.serialization.Serializable

/**
 * Result of git structural ingest.
 */
@Serializable
data class GitStructureIngestResult(
    val status: String,
    val nodesCreated: Int = 0,
    val edgesCreated: Int = 0,
    val nodesUpdated: Int = 0,
    val repositoryKey: String = "",
    val branchKey: String = "",
    val filesIndexed: Int = 0,
    val classesIndexed: Int = 0,
)
