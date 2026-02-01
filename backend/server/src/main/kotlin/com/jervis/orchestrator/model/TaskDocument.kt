package com.jervis.orchestrator.model

import com.jervis.dto.TaskStateEnum
import com.jervis.dto.TaskTypeEnum
import com.jervis.types.ClientId
import com.jervis.types.ProjectId
import com.jervis.types.SourceUrn
import com.jervis.types.TaskId
import kotlinx.serialization.Serializable
import java.time.Instant

/**
 * TaskDocument - The single state document for JERVIS Orchestrator.
 * 
 * It serves as:
 * - Session state (STM)
 * - Program state (task registry)
 * - Evidence pack storage
 * - Work queue
 * - Resume pointer
 */
@Serializable
data class TaskDocument(
    val id: String,
    val clientId: String,
    val projectId: String? = null,
    val type: String,
    val content: String,
    val state: String,
    val correlationId: String,
    val sourceUrn: String,
    val createdAt: String,
    
    // Orchestrator Extensions
    val resumePoint: TaskResumePoint? = null,
    val stmSummary: String = "",
    val rollingSummary: String = "", // Added for progressive compression
    val evidence: List<EvidenceItem> = emptyList(),
    val programState: ProgramState? = null,
    val stagingLtm: List<EvidenceItem> = emptyList(),
    val pendingQuestions: List<PendingUserQuestion> = emptyList()
)

@Serializable
data class CodeMapSummary(
    val entrypoints: List<String> = emptyList(),
    val modules: List<String> = emptyList(),
    val symbols: List<String> = emptyList(),
    val affectedFiles: List<String> = emptyList(),
    val summary: String = ""
)

@Serializable
data class PendingUserQuestion(
    val question: String,
    val context: String? = null,
    val workItemId: String? = null
)

@Serializable
data class TaskResumePoint(
    val phase: String,
    val cursor: String,
    val subCursor: String? = null
)

@Serializable
data class ProgramState(
    val tasks: List<WorkItemState> = emptyList(),
    val queue: List<String> = emptyList(), // task IDs
    val running: List<String> = emptyList(),
    val blocked: List<String> = emptyList()
)

@Serializable
data class WorkItemState(
    val id: String,
    val type: String,
    val status: String,
    val title: String,
    val readiness: ReadinessReport? = null,
    val dependencies: List<String> = emptyList(),
    val wave: Int = 0
)

@Serializable
data class ReadinessReport(
    val ready: Boolean,
    val statusGroup: String, // DRAFT, READY, DOING, REVIEW, BLOCKED, TERMINAL
    val missingRequirements: List<String> = emptyList(),
    val blockingRelations: List<String> = emptyList(),
    val commentDraft: String? = null,
    val transitionRequest: String? = null
)
