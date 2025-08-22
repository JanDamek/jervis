package com.jervis.entity.mongo

import org.bson.types.ObjectId
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.index.Indexed
import org.springframework.data.mongodb.core.mapping.Document
import java.time.Instant

/**
 * Persisted context snapshot bound to client and project.
 */
@Document(collection = "contexts")
data class ContextDocument(
    @Id
    val id: ObjectId = ObjectId.get(),
    @Indexed
    val clientId: ObjectId? = null,
    @Indexed
    val projectId: ObjectId? = null,
    val clientName: String? = null,
    val projectName: String? = null,
    val autoScope: Boolean = false,
    val englishText: String? = null,
    val sourceLanguage: String? = null,
    val createdAt: Instant = Instant.now(),
)
