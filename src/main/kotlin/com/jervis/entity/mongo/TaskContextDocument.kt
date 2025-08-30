package com.jervis.entity.mongo

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.bson.types.ObjectId
import org.springframework.data.annotation.Id
import org.springframework.data.annotation.Transient
import org.springframework.data.mongodb.core.index.Indexed
import org.springframework.data.mongodb.core.mapping.Document
import java.time.Instant

@Document(collection = "task_contexts")
data class TaskContextDocument(
    @Id
    val id: ObjectId = ObjectId.get(),
    @Indexed
    var clientId: ObjectId? = null,
    @Indexed
    var projectId: ObjectId? = null,
    var clientName: String? = null,
    var projectName: String? = null,
    var initialQuery: String,
    var originalLanguage: String? = null,
    @Transient
    var plans: Flow<PlanDocument> = emptyFlow(),
    var contextSummary: String? = null,
    var finalResult: String? = null,
    var failureReason: String? = null,
    val createdAt: Instant = Instant.now(),
    var updatedAt: Instant = Instant.now(),
    val quick: Boolean,
)
