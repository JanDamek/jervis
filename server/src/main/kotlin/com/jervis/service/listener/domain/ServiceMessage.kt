package com.jervis.service.listener.domain

import com.jervis.domain.authentication.ServiceTypeEnum
import org.bson.types.ObjectId
import java.time.Instant

/**
 * Represents a message or item retrieved from an external service
 */
data class ServiceMessage(
    val id: String,
    val serviceTypeEnum: ServiceTypeEnum,
    val clientId: ObjectId,
    val projectId: ObjectId?,
    val content: String,
    val author: String?,
    val timestamp: Instant,
    val metadata: Map<String, String> = emptyMap(),
    val threadId: String? = null,
    val channelId: String? = null,
    val attachments: List<ServiceAttachment> = emptyList(),
)
