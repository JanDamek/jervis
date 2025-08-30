package com.jervis.entity.mongo

import com.jervis.domain.plan.PlanStatus
import org.bson.types.ObjectId
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.index.Indexed
import org.springframework.data.mongodb.core.mapping.DBRef
import org.springframework.data.mongodb.core.mapping.Document
import java.time.Instant

@Document(collection = "plans")
data class PlanDocument(
    @Id
    val id: ObjectId = ObjectId.get(),
    @Indexed
    val contextId: ObjectId,
    var status: PlanStatus = PlanStatus.CREATED,
    @DBRef
    val steps: List<PlanStep> = emptyList(),
    val createdAt: Instant = Instant.now(),
    var updatedAt: Instant = Instant.now(),
)
