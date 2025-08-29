package com.jervis.entity.mongo

import com.jervis.domain.agent.Plan
import com.jervis.domain.agent.TaskStatus
import org.bson.types.ObjectId
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.index.Indexed
import org.springframework.data.mongodb.core.mapping.Document
import java.time.Instant

@Document(collection = "task_contexts")
data class TaskContextDocument(
    @Id
    val id: ObjectId = ObjectId.get(),
    @Indexed
    val contextId: ObjectId,
    @Indexed
    val clientId: ObjectId? = null,
    @Indexed
    val projectId: ObjectId? = null,
    val clientName: String? = null,
    val projectName: String? = null,
    var status: TaskStatus = TaskStatus.PLANNING,
    var initialQuery: String,
    var plan: Plan? = null,
    var workingMemory: Map<String, Any?> = mutableMapOf(),
    var finalResult: String? = null,
    var failureReason: String? = null,
    val createdAt: Instant = Instant.now(),
    var updatedAt: Instant = Instant.now(),
    var quick: Boolean = false,
)
