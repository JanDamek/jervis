package com.jervis.service.indexing.monitoring

import org.bson.types.ObjectId
import java.time.Instant

/**
 * Enum representing all available indexing steps with their metadata
 */
enum class IndexingStepType(
    val stepName: String,
    val description: String,
) {
    CODE_FILES("Code Files", "Indexing source code files"),
    TEXT_CONTENT("Text Content", "Indexing documentation and text files"),
    JOERN_ANALYSIS("Joern Analysis", "Running Joern code analysis"),
    GIT_HISTORY("Git History", "Indexing git commit history"),
    DEPENDENCIES("Dependencies", "Analyzing project dependencies"),
    CLASS_SUMMARIES("Class Summaries", "Generating class summaries"),
    COMPREHENSIVE_FILES("Comprehensive Files", "Deep analysis of source files"),
    DOCUMENTATION("Documentation", "Processing project documentation"),
    MEETING_TRANSCRIPTS("Meeting Transcripts", "Indexing meeting transcripts"),
    AUDIO_TRANSCRIPTS("Audio Transcripts", "Indexing audio transcripts"),
    CLIENT_UPDATE("Client Update", "Updating client descriptions"),
    PROJECT("Project Indexing", "Overall project indexing process"),
}

/**
 * Event published when indexing progress changes for a project
 */
data class IndexingProgressEvent(
    val projectId: ObjectId,
    val projectName: String,
    val stepType: IndexingStepType,
    val status: IndexingStepStatus,
    val progress: IndexingProgress? = null,
    val message: String? = null,
    val errorMessage: String? = null,
    val timestamp: Instant = Instant.now(),
    val logs: List<String> = emptyList(),
)

/**
 * Represents the status of an indexing step
 */
enum class IndexingStepStatus {
    PENDING,
    RUNNING,
    COMPLETED,
    FAILED,
    SKIPPED,
}

/**
 * Represents progress information for an indexing step
 */
data class IndexingProgress(
    val current: Int,
    val total: Int,
    val percentage: Double = if (total > 0) (current.toDouble() / total * 100) else 0.0,
    val estimatedTimeRemaining: Long? = null, // milliseconds
)

/**
 * Represents an indexing step with its current state
 */
data class IndexingStep(
    val stepType: IndexingStepType,
    val status: IndexingStepStatus = IndexingStepStatus.PENDING,
    val progress: IndexingProgress? = null,
    val message: String? = null,
    val errorMessage: String? = null,
    val startTime: Instant? = null,
    val endTime: Instant? = null,
    val logs: MutableList<String> = mutableListOf(),
    val subSteps: MutableList<IndexingStep> = mutableListOf(),
) {
    val duration: Long?
        get() =
            if (startTime != null && endTime != null) {
                endTime.toEpochMilli() - startTime.toEpochMilli()
            } else {
                null
            }

    val isActive: Boolean
        get() = status == IndexingStepStatus.RUNNING

    val isCompleted: Boolean
        get() = status == IndexingStepStatus.COMPLETED

    val isFailed: Boolean
        get() = status == IndexingStepStatus.FAILED
}

/**
 * Represents the overall indexing state for a project
 */
data class ProjectIndexingState(
    val projectId: ObjectId,
    val projectName: String,
    val status: IndexingStepStatus = IndexingStepStatus.PENDING,
    val startTime: Instant? = null,
    val endTime: Instant? = null,
    val steps: MutableList<IndexingStep> = mutableListOf(),
) {
    val duration: Long?
        get() =
            if (startTime != null && endTime != null) {
                endTime.toEpochMilli() - startTime.toEpochMilli()
            } else {
                null
            }

    val overallProgress: IndexingProgress?
        get() {
            val completedSteps = steps.count { it.isCompleted }
            val totalSteps = steps.size
            return if (totalSteps > 0) {
                IndexingProgress(completedSteps, totalSteps)
            } else {
                null
            }
        }

    val isActive: Boolean
        get() = status == IndexingStepStatus.RUNNING || steps.any { it.isActive }

    val isCompleted: Boolean
        get() = status == IndexingStepStatus.COMPLETED && steps.all { it.isCompleted || it.status == IndexingStepStatus.SKIPPED }

    val hasFailed: Boolean
        get() = status == IndexingStepStatus.FAILED || steps.any { it.isFailed }
}
