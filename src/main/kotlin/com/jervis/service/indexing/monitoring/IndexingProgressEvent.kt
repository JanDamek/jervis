package com.jervis.service.indexing.monitoring

import org.bson.types.ObjectId
import java.time.Instant

/**
 * Event published when indexing progress changes for a project
 */
data class IndexingProgressEvent(
    val projectId: ObjectId,
    val projectName: String,
    val stepId: String,
    val stepName: String,
    val status: IndexingStepStatus,
    val progress: IndexingProgress? = null,
    val message: String? = null,
    val errorMessage: String? = null,
    val timestamp: Instant = Instant.now(),
    val logs: List<String> = emptyList()
)

/**
 * Represents the status of an indexing step
 */
enum class IndexingStepStatus {
    PENDING,
    RUNNING,
    COMPLETED,
    FAILED,
    SKIPPED
}

/**
 * Represents progress information for an indexing step
 */
data class IndexingProgress(
    val current: Int,
    val total: Int,
    val percentage: Double = if (total > 0) (current.toDouble() / total * 100) else 0.0,
    val estimatedTimeRemaining: Long? = null // milliseconds
)

/**
 * Represents an indexing step with its current state
 */
data class IndexingStep(
    val id: String,
    val name: String,
    val description: String,
    val status: IndexingStepStatus = IndexingStepStatus.PENDING,
    val progress: IndexingProgress? = null,
    val message: String? = null,
    val errorMessage: String? = null,
    val startTime: Instant? = null,
    val endTime: Instant? = null,
    val logs: MutableList<String> = mutableListOf(),
    val subSteps: MutableList<IndexingStep> = mutableListOf()
) {
    val duration: Long?
        get() = if (startTime != null && endTime != null) {
            endTime.toEpochMilli() - startTime.toEpochMilli()
        } else null

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
    val steps: MutableList<IndexingStep> = mutableListOf()
) {
    val duration: Long?
        get() = if (startTime != null && endTime != null) {
            endTime.toEpochMilli() - startTime.toEpochMilli()
        } else null

    val overallProgress: IndexingProgress?
        get() {
            val completedSteps = steps.count { it.isCompleted }
            val totalSteps = steps.size
            return if (totalSteps > 0) {
                IndexingProgress(completedSteps, totalSteps)
            } else null
        }

    val isActive: Boolean
        get() = status == IndexingStepStatus.RUNNING || steps.any { it.isActive }

    val isCompleted: Boolean
        get() = status == IndexingStepStatus.COMPLETED && steps.all { it.isCompleted || it.status == IndexingStepStatus.SKIPPED }

    val hasFailed: Boolean
        get() = status == IndexingStepStatus.FAILED || steps.any { it.isFailed }
}