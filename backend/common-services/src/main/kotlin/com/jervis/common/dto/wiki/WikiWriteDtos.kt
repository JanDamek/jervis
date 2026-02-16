package com.jervis.common.dto.wiki

import com.jervis.common.dto.AuthType
import kotlinx.serialization.Serializable

// ==================== WRITE RPC DTOs ====================

@Serializable
data class WikiCreatePageRpcRequest(
    val baseUrl: String,
    val authType: AuthType,
    val basicUsername: String? = null,
    val basicPassword: String? = null,
    val bearerToken: String? = null,
    val cloudId: String? = null,
    val spaceKey: String,
    val title: String,
    val content: String,
    val parentPageId: String? = null,
)

@Serializable
data class WikiUpdatePageRpcRequest(
    val baseUrl: String,
    val authType: AuthType,
    val basicUsername: String? = null,
    val basicPassword: String? = null,
    val bearerToken: String? = null,
    val cloudId: String? = null,
    val pageId: String,
    val title: String,
    val content: String,
    val version: Int,
)
