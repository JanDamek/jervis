package com.jervis.knowledgebase.model

import kotlinx.serialization.Serializable

/**
 * Single git commit metadata for KB ingest.
 */
@Serializable
data class GitCommitInfo(
    val hash: String,
    val message: String,
    val author: String,
    val date: String,
    val branch: String,
    val parentHash: String? = null,
    val filesModified: List<String> = emptyList(),
    val filesCreated: List<String> = emptyList(),
    val filesDeleted: List<String> = emptyList(),
)

/**
 * Request to ingest structured git commit data into KB graph.
 *
 * Creates commit nodes in ArangoDB with edges to branch and file nodes.
 * Optional diffContent is ingested as RAG chunks for fulltext search.
 */
@Serializable
data class GitCommitIngestRequest(
    val clientId: String,
    val projectId: String,
    val repositoryIdentifier: String,
    val branch: String,
    val commits: List<GitCommitInfo>,
    val diffContent: String? = null,
)

/**
 * Result of git commit ingest.
 */
@Serializable
data class GitCommitIngestResult(
    val status: String,
    val commitsIngested: Int = 0,
    val nodesCreated: Int = 0,
    val edgesCreated: Int = 0,
    val ragChunks: Int = 0,
)
