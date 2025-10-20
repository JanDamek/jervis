package com.jervis.dto.email

import com.jervis.domain.email.EmailProvider
import kotlinx.serialization.Serializable
import org.bson.types.ObjectId
import java.time.Instant

@Serializable
data class EmailAccountDto(
    val id: String? = null,
    val clientId: String,
    val projectId: String? = null,
    val provider: EmailProvider,
    val displayName: String,
    val email: String,
    val username: String? = null,
    val serverHost: String? = null,
    val serverPort: Int? = null,
    val useSsl: Boolean = true,
    val isActive: Boolean = true,
    val createdAt: Instant? = null,
    val updatedAt: Instant? = null,
)

@Serializable
data class CreateOrUpdateEmailAccountRequest(
    val clientId: String,
    val projectId: String? = null,
    val provider: EmailProvider,
    val displayName: String,
    val email: String,
    val username: String? = null,
    val serverHost: String? = null,
    val serverPort: Int? = null,
    val useSsl: Boolean = true,
)

@Serializable
data class ValidateResponse(
    val ok: Boolean,
    val message: String? = null,
)
