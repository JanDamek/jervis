package com.jervis.entity.mongo

import org.bson.types.ObjectId
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.index.Indexed
import org.springframework.data.mongodb.core.mapping.Document
import java.time.Instant

@Document(collection = "plans")
data class PlanDocument(
    @Id
    val id: ObjectId = ObjectId.get(),
    @Indexed
    val contextId: ObjectId,
    val status: PlanStatus = PlanStatus.CREATED,
    val steps: List<PlanStep> = emptyList(),
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now(),
)

enum class PlanStatus {
    CREATED,
    RUNNING,
    COMPLETED,
    FAILED,
}

data class PlanStep(
    val name: String,
    val parameters: Map<String, Any> = emptyMap(),
    val status: StepStatus = StepStatus.PENDING,
    val output: String? = null,
)

enum class StepStatus {
    PENDING,
    DONE,
    ERROR,
}
