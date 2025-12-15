package com.jervis.common.dto.atlassian

data class AtlassianConnection(
    val baseUrl: String,
    val auth: AtlassianAuth,
    val timeoutMs: Long? = null,
)

sealed class AtlassianAuth {
    data object None : AtlassianAuth()

    data class Basic(
        val username: String,
        val password: String,
    ) : AtlassianAuth()

    data class Bearer(
        val token: String,
    ) : AtlassianAuth()
}
