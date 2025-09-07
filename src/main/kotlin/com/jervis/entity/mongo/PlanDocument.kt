package com.jervis.entity.mongo

import com.jervis.domain.plan.Plan
import com.jervis.domain.plan.PlanStatus
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
    var contextId: ObjectId,
    val originalQuestion: String,
    val originalLanguage: String,
    val englishQuestion: String,
    var status: PlanStatus = PlanStatus.CREATED,
    var contextSummary: String? = null,
    var finalAnswer: String? = null,
    val createdAt: Instant = Instant.now(),
    var updatedAt: Instant = Instant.now(),
) {
    fun toDomain(steps: List<PlanStepDocument> = emptyList()): Plan =
        Plan(
            id = this.id,
            contextId = this.contextId,
            originalQuestion = this.originalQuestion,
            originalLanguage = this.originalLanguage,
            englishQuestion = this.englishQuestion,
            status = this.status,
            steps = steps.map { it.toDomain() },
            contextSummary = this.contextSummary,
            finalAnswer = this.finalAnswer,
            createdAt = this.createdAt,
            updatedAt = this.updatedAt,
        )

    companion object {
        fun fromDomain(plan: Plan): PlanDocument =
            PlanDocument(
                id = plan.id,
                contextId = plan.contextId,
                originalQuestion = plan.originalQuestion,
                originalLanguage = plan.originalLanguage,
                englishQuestion = plan.englishQuestion,
                status = plan.status,
                contextSummary = plan.contextSummary,
                finalAnswer = plan.finalAnswer,
                createdAt = plan.createdAt,
                updatedAt = plan.updatedAt,
            )
    }
}
