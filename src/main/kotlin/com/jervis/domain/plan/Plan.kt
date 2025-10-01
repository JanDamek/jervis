package com.jervis.domain.plan

import com.jervis.domain.execution.ExecutionNode
import org.bson.types.ObjectId
import java.time.Instant

data class Plan(
    val id: ObjectId,
    var contextId: ObjectId,
    val originalQuestion: String,
    val originalLanguage: String,
    val englishQuestion: String,
    val questionChecklist: List<String> = emptyList(),
    val investigationGuidance: List<String> = emptyList(),
    var status: PlanStatus = PlanStatus.CREATED,
    var steps: List<PlanStep> = emptyList(), // Legacy flat step list for backward compatibility
    var executionTree: List<ExecutionNode> = emptyList(), // New tree-based execution model
    var contextSummary: String? = null,
    var finalAnswer: String? = null,
    val createdAt: Instant = Instant.now(),
    var updatedAt: Instant = Instant.now(),
)
