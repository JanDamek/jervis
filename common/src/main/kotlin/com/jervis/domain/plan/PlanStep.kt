package com.jervis.domain.plan

import com.jervis.serialization.ObjectIdSerializer
import kotlinx.serialization.Serializable
import org.bson.types.ObjectId

@Serializable
data class PlanStep(
    @Serializable(with = ObjectIdSerializer::class)
    val id: ObjectId,
    var order: Int = -1,
    @Serializable(with = ObjectIdSerializer::class)
    var planId: ObjectId,
    @Serializable(with = ObjectIdSerializer::class)
    var contextId: ObjectId,
    val stepToolName: String,
    val stepInstruction: String,
    val stepDependsOn: Int = -1,
    var status: StepStatus = StepStatus.PENDING,
    var toolResult: String? = null,
)
