package com.jervis.domain.email

import org.bson.types.ObjectId
import java.time.Instant

/**
 * Domain model for email account.
 * Represents business logic for email account configuration.
 */
data class EmailAccount(
    val id: ObjectId,
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
    val isActive: Boolean,
    val lastPolledAt: Instant?,
    val highestSeenUid: Long?,
) {
    fun hasValidImapConfig(): Boolean = serverHost != null && serverPort != null && password != null

    fun getImapUsername(): String = username ?: email
}
