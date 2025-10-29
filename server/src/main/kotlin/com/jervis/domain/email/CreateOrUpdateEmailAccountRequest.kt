package com.jervis.domain.email

import org.bson.types.ObjectId

data class CreateOrUpdateEmailAccountRequest(
    val clientId: ObjectId,
    val projectId: ObjectId? = null,
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
