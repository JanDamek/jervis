package com.jervis.atlassian.util

import com.jervis.common.dto.atlassian.AtlassianAuth
import com.jervis.common.dto.atlassian.AtlassianConnection
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.Base64

object ConnectionHeaderUtil {
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class Conn(
        val baseUrl: String,
        val auth: Auth,
        val timeoutMs: Long? = null,
    ) {
        @Serializable
        data class Auth(
            val type: String,
            val username: String? = null,
            val password: String? = null,
            val token: String? = null,
        )
    }

    fun parse(headerValue: String): AtlassianConnection {
        // Support raw JSON or Base64(JSON)
        val raw = runCatching { String(Base64.getDecoder().decode(headerValue)) }.getOrElse { headerValue }
        val dto = json.decodeFromString(Conn.serializer(), raw)
        val auth =
            when (dto.auth.type.uppercase()) {
                "BASIC" -> AtlassianAuth.Basic(dto.auth.username.orEmpty(), dto.auth.password.orEmpty())
                "BEARER" -> AtlassianAuth.Bearer(dto.auth.token.orEmpty())
                else -> AtlassianAuth.None
            }
        return AtlassianConnection(
            baseUrl = dto.baseUrl,
            auth = auth,
            timeoutMs = dto.timeoutMs,
        )
    }
}
