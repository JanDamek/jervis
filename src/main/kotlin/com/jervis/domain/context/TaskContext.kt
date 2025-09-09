package com.jervis.domain.context

import com.jervis.domain.plan.Plan
import com.jervis.entity.mongo.ClientDocument
import com.jervis.entity.mongo.ProjectDocument
import org.bson.types.ObjectId
import java.time.Instant

data class TaskContext(
    val id: ObjectId,
    var clientDocument: ClientDocument,
    var projectDocument: ProjectDocument,
    var name: String = "New Context",
    var plans: List<Plan> = emptyList(),
    val createdAt: Instant = Instant.now(),
    var updatedAt: Instant = Instant.now(),
    val quick: Boolean,
    var projectContextInfo: ProjectContextInfo? = null,
    var contextSummary: String = "",
) {
    override fun toString(): String = if (quick) "⚡ $name" else name
}
