package com.jervis.entity.mongo

import com.jervis.domain.authentication.ServiceType
import org.bson.types.ObjectId
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.index.CompoundIndex
import org.springframework.data.mongodb.core.index.CompoundIndexes
import org.springframework.data.mongodb.core.mapping.Document
import java.time.Instant

/**
 * MongoDB document storing encrypted service credentials.
 * Each client/project can have multiple credentials for different services.
 */
@Document(collection = "service_credentials")
@CompoundIndexes(
    CompoundIndex(name = "client_project_service", def = "{'clientId': 1, 'projectId': 1, 'serviceType': 1}"),
    CompoundIndex(name = "client_service", def = "{'clientId': 1, 'serviceType': 1}"),
)
data class ServiceCredentialsDocument(
    @Id
    val id: ObjectId = ObjectId.get(),
    val clientId: ObjectId,
    val projectId: ObjectId? = null,
    val serviceType: ServiceType,
    val accessToken: String? = null,
    val refreshToken: String? = null,
    val expiresAt: Instant? = null,
    val tokenType: String? = null,
    val scopes: List<String> = emptyList(),
    val username: String? = null,
    val serverId: String? = null,
    val workspace: String? = null,
    val additionalData: Map<String, String> = emptyMap(),
    val isActive: Boolean = true,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now(),
    val lastUsedAt: Instant? = null,
)
