package com.jervis.domain.error

import org.bson.types.ObjectId
import java.time.Instant

/**
 * Immutable domain model representing a captured server-side error.
 */
data class ErrorLog(
    val id: ObjectId = ObjectId(),
    val clientId: ObjectId? = null,
    val projectId: ObjectId? = null,
    val correlationId: String? = null,
    val message: String,
    val stackTrace: String? = null,
    val causeType: String? = null,
    val createdAt: Instant = Instant.now(),
)
