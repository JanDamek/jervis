package com.jervis.service.git

import com.jervis.types.ClientId
import kotlinx.serialization.Serializable

/**
 * Git/Bitbucket integration service (READ operations).
 * Currently a TODO/mock interface - implementation pending.
 */
interface GitService {
    // ==================== READ OPERATIONS ====================

    /**
     * Search repositories by query.
     */
    suspend fun searchRepositories(
        clientId: ClientId,
        query: String,
    ): List<Repository>

    /**
     * Get specific repository by key.
     */
    suspend fun getRepository(
        clientId: ClientId,
        repoKey: String,
    ): Repository

    /**
     * Get pull request by ID.
     */
    suspend fun getPullRequest(
        clientId: ClientId,
        prId: String,
    ): PullRequest

    /**
     * Search commits by query.
     */
    suspend fun searchCommits(
        clientId: ClientId,
        query: String,
        repo: String? = null,
    ): List<Commit>
}

// ==================== DATA MODELS ====================

@Serializable
data class Repository(
    val key: String,
    val name: String,
    val description: String?,
    val url: String,
    val defaultBranch: String,
)

@Serializable
data class PullRequest(
    val id: String,
    val title: String,
    val description: String?,
    val author: String,
    val sourceBranch: String,
    val targetBranch: String,
    val status: String, // OPEN, MERGED, DECLINED
    val created: String,
    val updated: String,
)

@Serializable
data class Commit(
    val hash: String,
    val message: String,
    val author: String,
    val date: String,
    val repository: String,
)
