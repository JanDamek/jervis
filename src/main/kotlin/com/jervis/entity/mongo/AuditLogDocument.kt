package com.jervis.entity.mongo

import org.bson.types.ObjectId
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.index.Indexed
import org.springframework.data.mongodb.core.mapping.Document
import java.time.Instant

/**
 * Audit log for LLM interactions (request + response).
 */
@Document(collection = "audit_logs")
data class AuditLogDocument(
    @Id
    val id: ObjectId = ObjectId.get(),
    @Indexed
    val type: AuditType,
    val inputText: String,
    val systemPrompt: String? = null,
    val userPrompt: String,
    val responseText: String? = null,
    val errorText: String? = null,
    val clientHint: String? = null,
    val projectHint: String? = null,
    val contextId: ObjectId? = null,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now(),
)

enum class AuditType {
    TRANSLATION_DETECTION,
    LANGUAGE_DETECT,
    TRANSLATION,
}
