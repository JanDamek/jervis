package com.jervis.entity.mongo

import com.jervis.domain.email.EmailProvider
import org.bson.types.ObjectId
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.index.CompoundIndex
import org.springframework.data.mongodb.core.index.CompoundIndexes
import org.springframework.data.mongodb.core.mapping.Document
import java.time.Instant

/**
 * Email account configuration stored per client/project.
 * Immutable snapshot model; updates create a new version via copy semantics.
 */
@Document(collection = "email_accounts")
@CompoundIndexes(
    CompoundIndex(name = "client_project_provider_idx", def = "{'clientId':1,'projectId':1,'provider':1}")
)
data class EmailAccountDocument(
    @Id val id: ObjectId = ObjectId.get(),
    val clientId: ObjectId,
    val projectId: ObjectId? = null,
    val provider: EmailProvider,
    val displayName: String,
    val email: String,
    val username: String? = null,
    val serverHost: String? = null,
    val serverPort: Int? = null,
    val useSsl: Boolean = true,
    /** Foreign key to ServiceCredentialsDocument that holds tokens/passwords */
    val credentialsId: ObjectId? = null,
    val isActive: Boolean = true,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now(),
)
