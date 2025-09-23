package com.jervis.service.indexing.monitoring

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
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
    private val eventPublisher: ApplicationEventPublisher
) {
    private val logger = KotlinLogging.logger {}
    
    private val projectStates = ConcurrentHashMap<ObjectId, ProjectIndexingState>()
    private val _progressFlow = MutableSharedFlow<IndexingProgressEvent>(extraBufferCapacity = 1000)
    
    /**
     * Flow of indexing progress events for real-time monitoring
     */
    val progressFlow: Flow<IndexingProgressEvent> = _progressFlow.asSharedFlow()

    /**
     * Start indexing for a project
     */
    suspend fun startProjectIndexing(projectId: ObjectId, projectName: String) {
        logger.info { "Starting indexing for project: $projectName ($projectId)" }
        
        val state = ProjectIndexingState(
            projectId = projectId,
            projectName = projectName,
            status = IndexingStepStatus.RUNNING,
            startTime = Instant.now(),
            steps = createIndexingSteps().toMutableList()
        )
        
        projectStates[projectId] = state
        
        publishEvent(IndexingProgressEvent(
            projectId = projectId,
            projectName = projectName,
            stepId = "project",
            stepName = "Project Indexing",
            status = IndexingStepStatus.RUNNING,
            message = "Starting project indexing process"
        ))
    }

    /**
     * Update step progress
     */
    suspend fun updateStepProgress(
        projectId: ObjectId,
        stepId: String,
        status: IndexingStepStatus,
        progress: IndexingProgress? = null,
        message: String? = null,
        errorMessage: String? = null,
        logs: List<String> = emptyList()
    ) {
        val state = projectStates[projectId] ?: return
        val step = findStep(state.steps, stepId) ?: return
        
        val updatedStep = step.copy(
            status = status,
            progress = progress,
            message = message,
            errorMessage = errorMessage,
            startTime = step.startTime ?: if (status == IndexingStepStatus.RUNNING) Instant.now() else null,
            endTime = if (status in listOf(IndexingStepStatus.COMPLETED, IndexingStepStatus.FAILED)) Instant.now() else null
        )
        
        // Add logs
        updatedStep.logs.addAll(logs)
        
        // Update the step in the state
        updateStepInState(state.steps, stepId, updatedStep)
        
        publishEvent(IndexingProgressEvent(
            projectId = projectId,
            projectName = state.projectName,
            stepId = stepId,
            stepName = step.name,
            status = status,
            progress = progress,
            message = message,
            errorMessage = errorMessage,
            logs = logs
        ))
        
        // Check if project is completed
        if (state.isCompleted && state.status != IndexingStepStatus.COMPLETED) {
            completeProjectIndexing(projectId)
        } else if (state.hasFailed && state.status != IndexingStepStatus.FAILED) {
            failProjectIndexing(projectId, "One or more indexing steps failed")
        }
    }

    /**
     * Add log message to a step
     */
    suspend fun addStepLog(projectId: ObjectId, stepId: String, logMessage: String) {
        val state = projectStates[projectId] ?: return
        val step = findStep(state.steps, stepId) ?: return
        
        val timestampedLog = "[${Instant.now()}] $logMessage"
        step.logs.add(timestampedLog)
        
        publishEvent(IndexingProgressEvent(
            projectId = projectId,
            projectName = state.projectName,
            stepId = stepId,
            stepName = step.name,
            status = step.status,
            logs = listOf(timestampedLog)
        ))
    }

    /**
     * Complete project indexing
     */
    suspend fun completeProjectIndexing(projectId: ObjectId) {
        val state = projectStates[projectId] ?: return
        val updatedState = state.copy(
            status = IndexingStepStatus.COMPLETED,
            endTime = Instant.now()
        )
        projectStates[projectId] = updatedState
        
        logger.info { "Completed indexing for project: ${state.projectName} ($projectId)" }
        
        publishEvent(IndexingProgressEvent(
            projectId = projectId,
            projectName = state.projectName,
            stepId = "project",
            stepName = "Project Indexing",
            status = IndexingStepStatus.COMPLETED,
            message = "Project indexing completed successfully"
        ))
    }

    /**
     * Fail project indexing
     */
    suspend fun failProjectIndexing(projectId: ObjectId, errorMessage: String) {
        val state = projectStates[projectId] ?: return
        val updatedState = state.copy(
            status = IndexingStepStatus.FAILED,
            endTime = Instant.now()
        )
        projectStates[projectId] = updatedState
        
        logger.error { "Failed indexing for project: ${state.projectName} ($projectId) - $errorMessage" }
        
        publishEvent(IndexingProgressEvent(
            projectId = projectId,
            projectName = state.projectName,
            stepId = "project",
            stepName = "Project Indexing",
            status = IndexingStepStatus.FAILED,
            errorMessage = errorMessage
        ))
    }

    /**
     * Get current state for a project
     */
    fun getProjectState(projectId: ObjectId): ProjectIndexingState? = projectStates[projectId]

    /**
     * Get all current project states
     */
    fun getAllProjectStates(): Map<ObjectId, ProjectIndexingState> = projectStates.toMap()

    /**
     * Clear completed projects older than specified time
     */
    fun cleanupOldStates(olderThanHours: Long = 24) {
        val cutoff = Instant.now().minusSeconds(olderThanHours * 3600)
        projectStates.entries.removeIf { (_, state) ->
            state.isCompleted && (state.endTime?.isBefore(cutoff) == true)
        }
    }

    private fun createIndexingSteps(): List<IndexingStep> = listOf(
        IndexingStep("code_files", "Code Files", "Indexing source code files"),
        IndexingStep("text_content", "Text Content", "Indexing documentation and text files"),
        IndexingStep("joern_analysis", "Joern Analysis", "Running Joern code analysis"),
        IndexingStep("git_history", "Git History", "Indexing git commit history"),
        IndexingStep("dependencies", "Dependencies", "Analyzing project dependencies"),
        IndexingStep("class_summaries", "Class Summaries", "Generating class summaries"),
        IndexingStep("comprehensive_files", "Comprehensive Files", "Deep analysis of source files"),
        IndexingStep("documentation", "Documentation", "Processing project documentation"),
        IndexingStep("meeting_transcripts", "Meeting Transcripts", "Indexing meeting transcripts"),
        IndexingStep("client_update", "Client Update", "Updating client descriptions")
    )

    private fun findStep(steps: List<IndexingStep>, stepId: String): IndexingStep? {
        steps.forEach { step ->
            if (step.id == stepId) return step
            val subStep = findStep(step.subSteps, stepId)
            if (subStep != null) return subStep
        }
        return null
    }

    private fun updateStepInState(steps: MutableList<IndexingStep>, stepId: String, updatedStep: IndexingStep) {
        for (i in steps.indices) {
            val step = steps[i]
            if (step.id == stepId) {
                steps[i] = updatedStep
                return
            }
            updateStepInState(step.subSteps, stepId, updatedStep)
        }
    }

    private suspend fun publishEvent(event: IndexingProgressEvent) {
        try {
            _progressFlow.emit(event)
            eventPublisher.publishEvent(event)
        } catch (e: Exception) {
            logger.error(e) { "Failed to publish indexing progress event" }
        }
    }
}