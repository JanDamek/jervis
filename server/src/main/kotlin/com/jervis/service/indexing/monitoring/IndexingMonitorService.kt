package com.jervis.service.indexing.monitoring

import com.jervis.domain.IndexingStepStatusEnum
import com.jervis.domain.IndexingStepTypeEnum
import com.jervis.dto.monitoring.IndexingProgressDto
import com.jervis.dto.monitoring.IndexingStepDto
import com.jervis.dto.monitoring.ProjectIndexingStateDto
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

    private val projectStates = ConcurrentHashMap<ObjectId, ProjectIndexingStateDto>()

    /**
     * Start indexing for a project
     */
    suspend fun startProjectIndexing(
        projectId: ObjectId,
        projectName: String,
    ) {
        logger.info { "Starting indexing for project: $projectName ($projectId)" }

        val state =
            ProjectIndexingStateDto(
                projectId = projectId.toHexString(),
                projectName = projectName,
                status = IndexingStepStatusEnum.RUNNING,
                startTime = Instant.now(),
                steps = createIndexingSteps().toMutableList(),
            )

        projectStates[projectId] = state

        publishEvent(
            IndexingProgressUpdate(
                projectId = projectId,
                projectName = projectName,
                stepType = IndexingStepTypeEnum.PROJECT,
                status = IndexingStepStatusEnum.RUNNING,
                message = "Starting project indexing process",
            ),
        )
    }

    /**
     * Update step progress
     */
    suspend fun updateStepProgress(
        projectId: ObjectId,
        stepType: IndexingStepTypeEnum,
        status: IndexingStepStatusEnum,
        progress: IndexingProgressDto? = null,
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
                startTime = step.startTime ?: if (status == IndexingStepStatusEnum.RUNNING) Instant.now() else null,
                endTime =
                    if (status in
                        listOf(
                            IndexingStepStatusEnum.COMPLETED,
                            IndexingStepStatusEnum.FAILED,
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
        if (state.isCompleted && state.status != IndexingStepStatusEnum.COMPLETED) {
            completeProjectIndexing(projectId)
        } else if (state.hasFailed && state.status != IndexingStepStatusEnum.FAILED) {
            failProjectIndexing(projectId, "One or more indexing steps failed")
        }
    }

    /**
     * Add a log message to a step
     */
    suspend fun addStepLog(
        projectId: ObjectId,
        stepType: IndexingStepTypeEnum,
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
                status = IndexingStepStatusEnum.COMPLETED,
                endTime = Instant.now(),
            )
        projectStates[projectId] = updatedState

        logger.info { "Completed indexing for project: ${state.projectName} ($projectId)" }

        publishEvent(
            IndexingProgressUpdate(
                projectId = projectId,
                projectName = state.projectName,
                stepType = IndexingStepTypeEnum.PROJECT,
                status = IndexingStepStatusEnum.COMPLETED,
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
                status = IndexingStepStatusEnum.FAILED,
                endTime = Instant.now(),
            )
        projectStates[projectId] = updatedState

        logger.error { "Failed indexing for project: ${state.projectName} ($projectId) - $errorMessage" }

        publishEvent(
            IndexingProgressUpdate(
                projectId = projectId,
                projectName = state.projectName,
                stepType = IndexingStepTypeEnum.PROJECT,
                status = IndexingStepStatusEnum.FAILED,
                errorMessage = errorMessage,
            ),
        )
    }

    /**
     * Get all current project states
     */
    fun getAllProjectStates(): Map<ObjectId, ProjectIndexingStateDto> = projectStates.toMap()

    private fun createIndexingSteps(): List<IndexingStepDto> =
        listOf(
            IndexingStepDto(IndexingStepTypeEnum.CODE_FILES),
            IndexingStepDto(IndexingStepTypeEnum.TEXT_CONTENT),
            IndexingStepDto(IndexingStepTypeEnum.JOERN_ANALYSIS),
            IndexingStepDto(IndexingStepTypeEnum.GIT_HISTORY),
            IndexingStepDto(IndexingStepTypeEnum.DEPENDENCIES),
            IndexingStepDto(IndexingStepTypeEnum.CLASS_SUMMARIES),
            IndexingStepDto(IndexingStepTypeEnum.COMPREHENSIVE_FILES),
            IndexingStepDto(IndexingStepTypeEnum.DOCUMENTATION),
            IndexingStepDto(IndexingStepTypeEnum.MEETING_TRANSCRIPTS),
            IndexingStepDto(IndexingStepTypeEnum.AUDIO_TRANSCRIPTS),
            IndexingStepDto(IndexingStepTypeEnum.CLIENT_UPDATE),
        )

    private fun findStep(
        steps: List<IndexingStepDto>,
        stepType: IndexingStepTypeEnum,
    ): IndexingStepDto? {
        steps.forEach { step ->
            if (step.stepType == stepType) return step
            val subStep = findStep(step.subSteps, stepType)
            if (subStep != null) return subStep
        }
        return null
    }

    private fun updateStepInState(
        steps: MutableList<IndexingStepDto>,
        stepType: IndexingStepTypeEnum,
        updatedStep: IndexingStepDto,
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
