package com.jervis.domain.plan

import org.bson.types.ObjectId
import java.time.Instant

data class Plan(
    val id: ObjectId,
    var contextId: ObjectId,
    val originalQuestion: String,
    val originalLanguage: String,
    val englishQuestion: String,
    var status: PlanStatus = PlanStatus.CREATED,
    var steps: List<PlanStep> = emptyList(),
    var contextSummary: String? = null,
    var finalAnswer: String? = null,
    val createdAt: Instant = Instant.now(),
    var updatedAt: Instant = Instant.now(),
)
