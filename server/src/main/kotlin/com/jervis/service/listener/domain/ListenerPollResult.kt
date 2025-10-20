package com.jervis.service.listener.domain

import com.jervis.domain.authentication.ServiceTypeEnum
import org.bson.types.ObjectId

/**
 * Result of a listener poll operation
 */
data class ListenerPollResult(
    val serviceTypeEnum: ServiceTypeEnum,
    val clientId: ObjectId,
    val projectId: ObjectId?,
    val newMessages: List<ServiceMessage>,
    val deletedMessageIds: List<String> = emptyList(),
    val error: String? = null,
)
