package com.jervis.entity.mongo

import org.bson.types.ObjectId
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.index.Indexed
import org.springframework.data.mongodb.core.mapping.DBRef
import org.springframework.data.mongodb.core.mapping.Document
import java.time.Instant

@Document(collection = "task_contexts")
data class TaskContextDocument(
    @Id
    val id: ObjectId = ObjectId.get(),
    @Indexed
    val contextId: ObjectId = ObjectId.get(),
    @Indexed
    var clientId: ObjectId? = null,
    @Indexed
    var projectId: ObjectId? = null,
    var clientName: String? = null,
    var projectName: String? = null,
    var initialQuery: String,
    var originalLanguage: String? = null,
    @DBRef
    var plan: PlanDocument? = null,
    var contextSummary: String? = null,
    var finalResult: String? = null,
    var failureReason: String? = null,
    val createdAt: Instant = Instant.now(),
    var updatedAt: Instant = Instant.now(),
    var quick: Boolean = false,
)
