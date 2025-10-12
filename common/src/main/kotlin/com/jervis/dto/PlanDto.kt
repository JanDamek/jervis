package com.jervis.dto

import com.jervis.domain.plan.PlanStatus
import kotlinx.serialization.Serializable

@Serializable
data class PlanDto(
    val id: String,
    val contextId: String,
    val originalQuestion: String,
    val originalLanguage: String,
    val englishQuestion: String,
    val questionChecklist: List<String> = emptyList(),
    val initialRagQueries: List<String> = emptyList(),
    val status: PlanStatus = PlanStatus.CREATED,
    val steps: List<PlanStepDto> = emptyList(),
    val contextSummary: String? = null,
    val finalAnswer: String? = null,
    val thinkingSequence: List<String> = emptyList(),
    val createdAt: String,
    val updatedAt: String,
)
