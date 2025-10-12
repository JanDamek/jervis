package com.jervis.domain.context

import com.jervis.domain.plan.Plan
import com.jervis.domain.project.ProjectContextInfo
import com.jervis.entity.mongo.ClientDocument
import com.jervis.entity.mongo.ProjectDocument
import com.jervis.serialization.InstantSerializer
import com.jervis.serialization.ObjectIdSerializer
import kotlinx.serialization.Serializable
import org.bson.types.ObjectId
import java.time.Instant

@Serializable
data class TaskContext(
    @Serializable(with = ObjectIdSerializer::class)
    val id: ObjectId,
    var clientDocument: ClientDocument,
    var projectDocument: ProjectDocument,
    var name: String = "New Context",
    var plans: List<Plan> = emptyList(),
    @Serializable(with = InstantSerializer::class)
    val createdAt: Instant = Instant.now(),
    @Serializable(with = InstantSerializer::class)
    var updatedAt: Instant = Instant.now(),
    val quick: Boolean,
    var projectContextInfo: ProjectContextInfo? = null,
    var contextSummary: String = "",
) {
    override fun toString(): String = if (quick) "⚡ $name" else name
}
