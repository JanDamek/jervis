package com.jervis.entity.mongo

import com.jervis.domain.email.EmailProviderEnum
import org.bson.types.ObjectId
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.index.CompoundIndex
import org.springframework.data.mongodb.core.index.CompoundIndexes
import org.springframework.data.mongodb.core.mapping.Document
import java.time.Instant

/**
 * Email account configuration with credentials stored directly.
 * Each client/project can have multiple email accounts.
 * Credentials are stored unencrypted (internal project).
 */
@Document(collection = "email_accounts")
@CompoundIndexes(
    CompoundIndex(name = "client_project_provider_idx", def = "{'clientId':1,'projectId':1,'provider':1}"),
    CompoundIndex(name = "client_active_idx", def = "{'clientId':1,'isActive':1}"),
    CompoundIndex(name = "active_lastIndexed_idx", def = "{'isActive':1,'lastIndexedAt':1}"),
)
data class EmailAccountDocument(
    @Id val id: ObjectId = ObjectId.get(),
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
    val accessToken: String? = null,
    val refreshToken: String? = null,
    val tokenExpiresAt: Instant? = null,
    val oauthScopes: List<String> = emptyList(),
    val isActive: Boolean = true,
    val lastPolledAt: Instant? = null,
    val lastIndexedAt: Instant? = null,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now(),
)
