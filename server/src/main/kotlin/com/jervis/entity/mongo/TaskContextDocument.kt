package com.jervis.entity.mongo

import com.jervis.common.Constants.GLOBAL_ID
import com.jervis.domain.context.TaskContext
import com.jervis.domain.plan.Plan
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
    var clientId: ObjectId,
    @Indexed
    var projectId: ObjectId,
    var clientName: String,
    var projectName: String,
    var name: String = "New Context",
    val createdAt: Instant = Instant.now(),
    var updatedAt: Instant = Instant.now(),
    val quick: Boolean,
) {
    fun toDomain(plans: List<Plan> = emptyList()): TaskContext =
        TaskContext(
            id = this.id,
            plans = plans,
            clientDocument =
                ClientDocument(
                    id = this.clientId ?: GLOBAL_ID,
                    name = this.clientName ?: "",
                ),
            projectDocument =
                ProjectDocument(
                    id = this.projectId ?: ObjectId.get(),
                    clientId = this.clientId ?: GLOBAL_ID,
                    name = this.projectName ?: "",
                ),
            name = this.name,
            createdAt = this.createdAt,
            updatedAt = this.updatedAt,
            quick = this.quick,
        )

    companion object {
        fun fromDomain(taskContext: TaskContext): TaskContextDocument =
            TaskContextDocument(
                id = taskContext.id,
                clientId = taskContext.clientDocument.id,
                projectId = taskContext.projectDocument.id,
                clientName = taskContext.clientDocument.name,
                projectName = taskContext.projectDocument.name,
                name = taskContext.name,
                createdAt = taskContext.createdAt,
                updatedAt = taskContext.updatedAt,
                quick = taskContext.quick,
            )
    }
}
