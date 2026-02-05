package com.jervis.common.dto.repository

import com.jervis.common.dto.AuthType
import kotlinx.serialization.Serializable

@Serializable
data class RepositoryUserRequest(
    val baseUrl: String,
    val authType: AuthType,
    val basicUsername: String? = null,
    val basicPassword: String? = null,
    val bearerToken: String? = null
)

@Serializable
data class RepositoryUserDto(
    val id: String,
    val username: String,
    val displayName: String,
    val email: String? = null
)

@Serializable
data class RepositoryListRequest(
    val baseUrl: String,
    val authType: AuthType,
    val basicUsername: String? = null,
    val basicPassword: String? = null,
    val bearerToken: String? = null,
    val maxResults: Int = 100
)

@Serializable
data class RepositoryListResponse(
    val repositories: List<RepositoryDto>
)

@Serializable
data class RepositoryRequest(
    val baseUrl: String,
    val authType: AuthType,
    val basicUsername: String? = null,
    val basicPassword: String? = null,
    val bearerToken: String? = null,
    val owner: String,
    val repo: String
)

@Serializable
data class RepositoryResponse(
    val repository: RepositoryDto
)

@Serializable
data class RepositoryDto(
    val id: String,
    val name: String,
    val fullName: String,
    val description: String? = null,
    val cloneUrl: String,
    val sshUrl: String,
    val webUrl: String,
    val defaultBranch: String = "main",
    val isPrivate: Boolean = false
)

@Serializable
data class RepositoryIssuesRequest(
    val baseUrl: String,
    val authType: AuthType,
    val basicUsername: String? = null,
    val basicPassword: String? = null,
    val bearerToken: String? = null,
    val owner: String,
    val repo: String,
    val maxResults: Int = 100
)

@Serializable
data class RepositoryIssuesResponse(
    val issues: List<RepositoryIssueDto>
)

@Serializable
data class RepositoryIssueDto(
    val id: String,
    val number: Int,
    val title: String,
    val description: String? = null,
    val state: String,
    val url: String,
    val created: String,
    val updated: String
)

@Serializable
data class RepositoryFileRequest(
    val baseUrl: String,
    val authType: AuthType,
    val basicUsername: String? = null,
    val basicPassword: String? = null,
    val bearerToken: String? = null,
    val owner: String,
    val repo: String,
    val path: String,
    val ref: String? = null // branch/tag/commit
)

@Serializable
data class RepositoryFileResponse(
    val content: String,
    val encoding: String = "base64",
    val size: Long,
    val path: String
)
