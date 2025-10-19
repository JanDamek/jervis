package com.jervis.entity.mongo

import com.jervis.domain.authentication.ServiceTypeEnum
import org.bson.types.ObjectId
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.index.CompoundIndex
import org.springframework.data.mongodb.core.index.CompoundIndexes
import org.springframework.data.mongodb.core.index.Indexed
import org.springframework.data.mongodb.core.mapping.Document
import java.time.Instant

/**
 * MongoDB document tracking messages indexed from external services
 * Keeps track of what has been indexed into RAG and allows for sync/cleanup
 */
@Document(collection = "service_messages")
@CompoundIndexes(
    CompoundIndex(name = "client_project_service", def = "{'clientId': 1, 'projectId': 1, 'serviceType': 1}"),
    CompoundIndex(name = "service_message_id", def = "{'serviceType': 1, 'serviceMessageId': 1}", unique = true),
    CompoundIndex(name = "indexed_at", def = "{'indexedAt': 1}"),
    CompoundIndex(name = "deleted", def = "{'isDeleted': 1, 'deletedAt': 1}"),
)
data class ServiceMessageDocument(
    @Id
    val id: ObjectId = ObjectId.get(),
    val clientId: ObjectId,
    val projectId: ObjectId?,
    val serviceTypeEnum: ServiceTypeEnum,
    @Indexed
    val serviceMessageId: String,
    val threadId: String? = null,
    val channelId: String? = null,
    val author: String? = null,
    val content: String,
    val contentHash: String,
    val messageTimestamp: Instant,
    val metadata: Map<String, String> = emptyMap(),
    val isIndexedInRag: Boolean = false,
    val ragDocumentIds: List<String> = emptyList(),
    val indexedAt: Instant? = null,
    val lastCheckedAt: Instant = Instant.now(),
    val isDeleted: Boolean = false,
    val deletedAt: Instant? = null,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now(),
)
