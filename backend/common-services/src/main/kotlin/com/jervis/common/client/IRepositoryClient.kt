package com.jervis.common.client

import com.jervis.common.dto.repository.*
import kotlinx.rpc.annotations.Rpc

/**
 * Generic Source Code Repository interface.
 * Can be implemented by GitHub, GitLab, Bitbucket, etc.
 */
@Rpc
interface IRepositoryClient {
    /**
     * Get authenticated user info
     */
    suspend fun getUser(request: RepositoryUserRequest): RepositoryUserDto

    /**
     * List repositories available to user
     */
    suspend fun listRepositories(request: RepositoryListRequest): RepositoryListResponse

    /**
     * Get single repository details
     */
    suspend fun getRepository(request: RepositoryRequest): RepositoryResponse

    /**
     * List issues for repository (if platform supports it)
     */
    suspend fun listIssues(request: RepositoryIssuesRequest): RepositoryIssuesResponse

    /**
     * Get repository content/file
     */
    suspend fun getFile(request: RepositoryFileRequest): RepositoryFileResponse
}
