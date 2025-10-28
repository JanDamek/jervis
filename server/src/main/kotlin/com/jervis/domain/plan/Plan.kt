package com.jervis.domain.plan

import com.jervis.domain.project.ProjectContextInfo
import com.jervis.entity.ClientDocument
import com.jervis.entity.ProjectDocument
import org.bson.types.ObjectId

data class Plan(
    val id: ObjectId,
    val taskInstruction: String,
    val originalLanguage: String,
    val englishInstruction: String,
    val questionChecklist: List<String> = emptyList(),
    val initialRagQueries: List<String> = emptyList(),
    var status: PlanStatusEnum = PlanStatusEnum.CREATED,
    var steps: List<PlanStep> = emptyList(),
    var contextSummary: String? = null,
    var finalAnswer: String? = null,
    var thinkingSequence: List<String> = emptyList(),
    val clientDocument: ClientDocument,
    val projectDocument: ProjectDocument? = null,
    val quick: Boolean,
    val backgroundMode: Boolean = false,
    var projectContextInfo: ProjectContextInfo? = null,
    val originPendingTaskId: ObjectId? = null,
) {
    val clientId: ObjectId
        get() = clientDocument.id

    val projectId: ObjectId?
        get() = projectDocument?.id

    override fun toString(): String =
        when {
            backgroundMode -> "🔄 $taskInstruction (background)"
            quick -> "⚡ $taskInstruction"
            else -> taskInstruction
        }
}
