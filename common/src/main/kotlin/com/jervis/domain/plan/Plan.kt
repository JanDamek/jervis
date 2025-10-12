package com.jervis.domain.plan

import com.jervis.serialization.InstantSerializer
import com.jervis.serialization.ObjectIdSerializer
import kotlinx.serialization.Serializable
import org.bson.types.ObjectId
import java.time.Instant

@Serializable
data class Plan(
    @Serializable(with = ObjectIdSerializer::class)
    val id: ObjectId,
    @Serializable(with = ObjectIdSerializer::class)
    var contextId: ObjectId,
    val originalQuestion: String,
    val originalLanguage: String,
    val englishQuestion: String,
    val questionChecklist: List<String> = emptyList(),
    val initialRagQueries: List<String> = emptyList(),
    var status: PlanStatus = PlanStatus.CREATED,
    var steps: List<PlanStep> = emptyList(),
    var contextSummary: String? = null,
    var finalAnswer: String? = null,
    var thinkingSequence: List<String> = emptyList(),
    @Serializable(with = InstantSerializer::class)
    val createdAt: Instant = Instant.now(),
    @Serializable(with = InstantSerializer::class)
    var updatedAt: Instant = Instant.now(),
)
