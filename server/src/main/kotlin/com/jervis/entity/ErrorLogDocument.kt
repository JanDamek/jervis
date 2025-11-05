package com.jervis.entity

import com.jervis.domain.error.ErrorLog
import org.bson.types.ObjectId
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.index.Indexed
import org.springframework.data.mongodb.core.mapping.Document
import java.time.Instant

@Document(collection = "error_logs")
data class ErrorLogDocument(
    @Id val id: ObjectId = ObjectId(),
    @Indexed val clientId: ObjectId? = null,
    @Indexed val projectId: ObjectId? = null,
    val correlationId: String? = null,
    val message: String,
    val stackTrace: String? = null,
    val causeType: String? = null,
    @Indexed val createdAt: Instant = Instant.now(),
) {
    fun toDomain(): ErrorLog =
        ErrorLog(
            id = id,
            clientId = clientId,
            projectId = projectId,
            correlationId = correlationId,
            message = message,
            stackTrace = stackTrace,
            causeType = causeType,
            createdAt = createdAt,
        )

    companion object {
        fun fromDomain(domain: ErrorLog): ErrorLogDocument =
            ErrorLogDocument(
                id = domain.id,
                clientId = domain.clientId,
                projectId = domain.projectId,
                correlationId = domain.correlationId,
                message = domain.message,
                stackTrace = domain.stackTrace,
                causeType = domain.causeType,
                createdAt = domain.createdAt,
            )
    }
}
