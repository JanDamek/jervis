package com.jervis.dto.email

import com.jervis.domain.email.EmailProviderEnum
import kotlinx.serialization.Serializable

@Serializable
data class CreateOrUpdateEmailAccountRequestDto(
    val clientId: String,
    val projectId: String? = null,
    val provider: EmailProviderEnum,
    val displayName: String,
    val description: String? = null,
    val email: String,
    val username: String? = null,
    val password: String? = null,
    val serverHost: String? = null,
    val serverPort: Int? = null,
    val useSsl: Boolean = true,
)
