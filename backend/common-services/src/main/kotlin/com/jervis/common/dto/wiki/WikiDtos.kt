package com.jervis.common.dto.wiki

import kotlinx.serialization.Serializable

@Serializable
data class WikiUserRequest(
    val baseUrl: String,
    val authType: String,
    val basicUsername: String? = null,
    val basicPassword: String? = null,
    val bearerToken: String? = null
)

@Serializable
data class WikiUserDto(
    val id: String,
    val username: String,
    val displayName: String,
    val email: String? = null
)

@Serializable
data class WikiSearchRequest(
    val baseUrl: String,
    val authType: String,
    val basicUsername: String? = null,
    val basicPassword: String? = null,
    val bearerToken: String? = null,
    val spaceKey: String? = null,
    val query: String? = null,
    val maxResults: Int = 100
)

@Serializable
data class WikiSearchResponse(
    val pages: List<WikiPageDto>,
    val total: Int
)

@Serializable
data class WikiPageRequest(
    val baseUrl: String,
    val authType: String,
    val basicUsername: String? = null,
    val basicPassword: String? = null,
    val bearerToken: String? = null,
    val pageId: String
)

@Serializable
data class WikiPageResponse(
    val page: WikiPageDto
)

@Serializable
data class WikiPageDto(
    val id: String,
    val title: String,
    val content: String? = null,
    val spaceKey: String? = null,
    val url: String,
    val created: String,
    val updated: String
)

@Serializable
data class WikiSpacesRequest(
    val baseUrl: String,
    val authType: String,
    val basicUsername: String? = null,
    val basicPassword: String? = null,
    val bearerToken: String? = null
)

@Serializable
data class WikiSpacesResponse(
    val spaces: List<WikiSpaceDto>
)

@Serializable
data class WikiSpaceDto(
    val id: String,
    val key: String,
    val name: String,
    val description: String? = null,
    val url: String? = null
)

@Serializable
data class WikiAttachmentRequest(
    val baseUrl: String,
    val authType: String,
    val basicUsername: String? = null,
    val basicPassword: String? = null,
    val bearerToken: String? = null,
    val attachmentId: String,
    val attachmentUrl: String
)
