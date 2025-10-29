package com.jervis.domain.email

import org.bson.types.ObjectId

/**
 * Domain model for creating a new email account.
 * Used in service layer business logic.
 */
data class CreateEmailAccountRequest(
    val clientId: ObjectId,
    val projectId: ObjectId?,
    val provider: EmailProviderEnum,
    val displayName: String,
    val description: String?,
    val email: String,
    val username: String?,
    val password: String?,
    val serverHost: String?,
    val serverPort: Int?,
    val useSsl: Boolean,
)
