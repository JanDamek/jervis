package com.jervis.service.indexing.monitoring

import mu.KotlinLogging
import org.bson.types.ObjectId
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Service for monitoring and managing indexing progress across all projects
 */
@Service
class IndexingMonitorService(
    private val eventPublisher: ApplicationEventPublisher,
) {
    private val logger = KotlinLogging.logger {}

    private val projectStates = ConcurrentHashMap<ObjectId, ProjectIndexingState>()

    /**
     * Start indexing for a project
     */
    suspend fun startProjectIndexing(
        projectId: ObjectId,
        projectName: String,
    ) {
        logger.info { "Starting indexing for project: $projectName ($projectId)" }

        val state =
            ProjectIndexingState(
                projectId = projectId.toHexString(),
                projectName = projectName,
                status = IndexingStepStatus.RUNNING,
                startTime = Instant.now(),
                steps = createIndexingSteps().toMutableList(),
            )

        projectStates[projectId] = state

        publishEvent(
            IndexingProgressUpdate(
                projectId = projectId,
                projectName = projectName,
                stepType = IndexingStepType.PROJECT,
                status = IndexingStepStatus.RUNNING,
                message = "Starting project indexing process",
            ),
        )
    }

    /**
     * Update step progress
     */
    suspend fun updateStepProgress(
        projectId: ObjectId,
        stepType: IndexingStepType,
        status: IndexingStepStatus,
        progress: IndexingProgress? = null,
        message: String? = null,
        errorMessage: String? = null,
        logs: List<String> = emptyList(),
    ) {
        val state = projectStates[projectId] ?: return
        val step = findStep(state.steps, stepType) ?: return

        val updatedStep =
            step.copy(
                status = status,
                progress = progress,
                message = message,
                errorMessage = errorMessage,
                startTime = step.startTime ?: if (status == IndexingStepStatus.RUNNING) Instant.now() else null,
                endTime =
                    if (status in
                        listOf(
                            IndexingStepStatus.COMPLETED,
                            IndexingStepStatus.FAILED,
                        )
                    ) {
                        Instant.now()
                    } else {
                        null
                    },
            )

        // Add logs
        updatedStep.logs.addAll(logs)

        // Update the step in the state
        updateStepInState(state.steps, stepType, updatedStep)

        publishEvent(
            IndexingProgressUpdate(
                projectId = projectId,
                projectName = state.projectName,
                stepType = stepType,
                status = status,
                progress = progress,
                message = message,
                errorMessage = errorMessage,
                logs = logs,
            ),
        )

        // Check if a project is completed
        if (state.isCompleted && state.status != IndexingStepStatus.COMPLETED) {
            completeProjectIndexing(projectId)
        } else if (state.hasFailed && state.status != IndexingStepStatus.FAILED) {
            failProjectIndexing(projectId, "One or more indexing steps failed")
        }
    }

    /**
     * Add a log message to a step
     */
    suspend fun addStepLog(
        projectId: ObjectId,
        stepType: IndexingStepType,
        logMessage: String,
    ) {
        val state = projectStates[projectId] ?: return
        val step = findStep(state.steps, stepType) ?: return

        val timestampedLog = "[${Instant.now()}] $logMessage"
        step.logs.add(timestampedLog)

        publishEvent(
            IndexingProgressUpdate(
                projectId = projectId,
                projectName = state.projectName,
                stepType = stepType,
                status = step.status,
                logs = listOf(timestampedLog),
            ),
        )
    }

    /**
     * Complete project indexing
     */
    suspend fun completeProjectIndexing(projectId: ObjectId) {
        val state = projectStates[projectId] ?: return
        val updatedState =
            state.copy(
                status = IndexingStepStatus.COMPLETED,
                endTime = Instant.now(),
            )
        projectStates[projectId] = updatedState

        logger.info { "Completed indexing for project: ${state.projectName} ($projectId)" }

        publishEvent(
            IndexingProgressUpdate(
                projectId = projectId,
                projectName = state.projectName,
                stepType = IndexingStepType.PROJECT,
                status = IndexingStepStatus.COMPLETED,
                message = "Project indexing completed successfully",
            ),
        )
    }

    /**
     * Fail project indexing
     */
    suspend fun failProjectIndexing(
        projectId: ObjectId,
        errorMessage: String,
    ) {
        val state = projectStates[projectId] ?: return
        val updatedState =
            state.copy(
                status = IndexingStepStatus.FAILED,
                endTime = Instant.now(),
            )
        projectStates[projectId] = updatedState

        logger.error { "Failed indexing for project: ${state.projectName} ($projectId) - $errorMessage" }

        publishEvent(
            IndexingProgressUpdate(
                projectId = projectId,
                projectName = state.projectName,
                stepType = IndexingStepType.PROJECT,
                status = IndexingStepStatus.FAILED,
                errorMessage = errorMessage,
            ),
        )
    }

    /**
     * Get all current project states
     */
    fun getAllProjectStates(): Map<ObjectId, ProjectIndexingState> = projectStates.toMap()

    private fun createIndexingSteps(): List<IndexingStep> =
        listOf(
            IndexingStep(IndexingStepType.CODE_FILES),
            IndexingStep(IndexingStepType.TEXT_CONTENT),
            IndexingStep(IndexingStepType.JOERN_ANALYSIS),
            IndexingStep(IndexingStepType.GIT_HISTORY),
            IndexingStep(IndexingStepType.DEPENDENCIES),
            IndexingStep(IndexingStepType.CLASS_SUMMARIES),
            IndexingStep(IndexingStepType.COMPREHENSIVE_FILES),
            IndexingStep(IndexingStepType.DOCUMENTATION),
            IndexingStep(IndexingStepType.MEETING_TRANSCRIPTS),
            IndexingStep(IndexingStepType.AUDIO_TRANSCRIPTS),
            IndexingStep(IndexingStepType.CLIENT_UPDATE),
        )

    private fun findStep(
        steps: List<IndexingStep>,
        stepType: IndexingStepType,
    ): IndexingStep? {
        steps.forEach { step ->
            if (step.stepType == stepType) return step
            val subStep = findStep(step.subSteps, stepType)
            if (subStep != null) return subStep
        }
        return null
    }

    private fun updateStepInState(
        steps: MutableList<IndexingStep>,
        stepType: IndexingStepType,
        updatedStep: IndexingStep,
    ) {
        for (i in steps.indices) {
            val step = steps[i]
            if (step.stepType == stepType) {
                steps[i] = updatedStep
                return
            }
            updateStepInState(step.subSteps, stepType, updatedStep)
        }
    }

    private suspend fun publishEvent(event: IndexingProgressUpdate) {
        try {
            eventPublisher.publishEvent(event)
        } catch (e: Exception) {
            logger.error(e) { "Failed to publish indexing progress event" }
        }
    }
}
