package com.jervis.entity.mongo

import com.jervis.domain.plan.PlanStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.bson.types.ObjectId
import org.springframework.data.annotation.Id
import org.springframework.data.annotation.Transient
import org.springframework.data.mongodb.core.index.Indexed
import org.springframework.data.mongodb.core.mapping.Document
import java.time.Instant

@Document(collection = "plans")
data class PlanDocument(
    @Id
    val id: ObjectId = ObjectId.get(),
    @Indexed
    var contextId: ObjectId,
    var status: PlanStatus = PlanStatus.CREATED,
    @Transient
    var steps: Flow<PlanStep> = emptyFlow(),
    val createdAt: Instant = Instant.now(),
    var updatedAt: Instant = Instant.now(),
)
