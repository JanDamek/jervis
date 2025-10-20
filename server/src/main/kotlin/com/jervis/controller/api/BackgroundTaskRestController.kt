package com.jervis.controller.api

import com.jervis.domain.background.BackgroundTask
import com.jervis.domain.background.BackgroundTaskStatus
import com.jervis.domain.background.BackgroundTaskType
import com.jervis.domain.background.TargetRef
import com.jervis.domain.background.TargetRefType
import com.jervis.entity.mongo.BackgroundArtifactDocument
import com.jervis.entity.mongo.BackgroundTaskDocument
import com.jervis.entity.mongo.CoverageSnapshotDocument
import com.jervis.repository.mongo.BackgroundArtifactMongoRepository
import com.jervis.repository.mongo.BackgroundTaskMongoRepository
import com.jervis.repository.mongo.CoverageSnapshotMongoRepository
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import mu.KotlinLogging
import org.bson.types.ObjectId
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * REST API for managing background cognitive tasks.
 */
@RestController
@RequestMapping("/api/background")
class BackgroundTaskRestController(
    private val taskRepository: BackgroundTaskMongoRepository,
    private val artifactRepository: BackgroundArtifactMongoRepository,
    private val coverageRepository: CoverageSnapshotMongoRepository,
) {
    private val logger = KotlinLogging.logger {}

    @GetMapping("/tasks")
    suspend fun listTasks(
        @RequestParam(required = false) status: String?,
    ): List<BackgroundTaskDto> {
        val tasks =
            if (status != null) {
                taskRepository.findByStatusOrderByCreatedAtDesc(status)
            } else {
                taskRepository
                    .findByStatusInOrderByPriorityAscCreatedAtAsc(
                        listOf(
                            BackgroundTaskStatus.PENDING.name,
                            BackgroundTaskStatus.RUNNING.name,
                            BackgroundTaskStatus.PARTIAL.name,
                        ),
                    )
            }

        return tasks.map { it.toDto() }.toList()
    }

    @GetMapping("/tasks/{taskId}")
    suspend fun getTask(
        @PathVariable taskId: String,
    ): BackgroundTaskDto? {
        val task = taskRepository.findById(ObjectId(taskId))
        return task?.toDto()
    }

    @PostMapping("/tasks")
    suspend fun createTask(
        @RequestBody request: CreateTaskRequest,
    ): BackgroundTaskDto {
        val task =
            BackgroundTask(
                taskType = request.taskType,
                targetRef =
                    TargetRef(
                        type = request.targetRefType,
                        id = request.targetRefId,
                    ),
                priority = request.priority ?: 3,
                status = BackgroundTaskStatus.PENDING,
                labels = request.labels ?: emptyList(),
                notes = request.notes,
            )

        val document = BackgroundTaskDocument.fromDomain(task)
        val saved = taskRepository.save(document)

        logger.info { "Created background task: ${saved.id} (${saved.taskType})" }

        return saved.toDto()
    }

    @GetMapping("/artifacts")
    suspend fun listArtifacts(
        @RequestParam(required = false) taskId: String?,
        @RequestParam(required = false) type: String?,
    ): List<BackgroundArtifactDto> {
        val artifacts =
            when {
                taskId != null -> artifactRepository.findByTaskIdOrderByCreatedAtDesc(ObjectId(taskId))
                type != null -> artifactRepository.findByTypeOrderByConfidenceDesc(type)
                else -> throw IllegalArgumentException("Either taskId or type must be specified")
            }

        return artifacts.map { it.toDto() }.toList()
    }

    @GetMapping("/coverage/{projectKey}")
    suspend fun getCoverage(
        @PathVariable projectKey: String,
    ): CoverageSnapshotDto? {
        val snapshot = coverageRepository.findFirstByProjectKeyOrderByCreatedAtDesc(projectKey)
        return snapshot?.toDto()
    }

    @GetMapping("/coverage")
    suspend fun listCoverage(
        @RequestParam projectKey: String,
        @RequestParam(defaultValue = "10") limit: Int,
    ): List<CoverageSnapshotDto> {
        val snapshots =
            coverageRepository
                .findByProjectKeyOrderByCreatedAtDesc(projectKey)
                .toList()
                .take(limit)

        return snapshots.map { it.toDto() }
    }

    @GetMapping("/stats")
    suspend fun getStats(): BackgroundStatsDto {
        val pending = taskRepository.countByStatus(BackgroundTaskStatus.PENDING.name)
        val running = taskRepository.countByStatus(BackgroundTaskStatus.RUNNING.name)
        val partial = taskRepository.countByStatus(BackgroundTaskStatus.PARTIAL.name)
        val completed = taskRepository.countByStatus(BackgroundTaskStatus.COMPLETED.name)
        val failed = taskRepository.countByStatus(BackgroundTaskStatus.SUSPENDED.name)

        return BackgroundStatsDto(
            pendingTasks = pending,
            runningTasks = running,
            partialTasks = partial,
            completedTasks = completed,
            failedTasks = failed,
        )
    }

    data class CreateTaskRequest(
        val taskType: BackgroundTaskType,
        val targetRefType: TargetRefType,
        val targetRefId: String,
        val priority: Int? = null,
        val labels: List<String>? = null,
        val notes: String? = null,
    )

    data class BackgroundTaskDto(
        val id: String,
        val taskType: String,
        val targetRef: String,
        val priority: Int,
        val status: String,
        val progress: Double,
        val retryCount: Int,
        val labels: List<String>,
        val notes: String?,
        val createdAt: String,
        val updatedAt: String,
    )

    data class BackgroundArtifactDto(
        val id: String,
        val taskId: String,
        val type: String,
        val payload: Map<String, Any>,
        val confidence: Double,
        val createdAt: String,
    )

    data class CoverageSnapshotDto(
        val id: String,
        val projectKey: String,
        val docs: Double,
        val tasks: Double,
        val code: Double,
        val meetings: Double,
        val overall: Double,
        val createdAt: String,
    )

    data class BackgroundStatsDto(
        val pendingTasks: Long,
        val runningTasks: Long,
        val partialTasks: Long,
        val completedTasks: Long,
        val failedTasks: Long,
    )

    private fun BackgroundTaskDocument.toDto() =
        BackgroundTaskDto(
            id = id.toHexString(),
            taskType = taskType,
            targetRef = targetRef,
            priority = priority,
            status = status,
            progress = progress,
            retryCount = retryCount,
            labels = labels,
            notes = notes,
            createdAt = createdAt.toString(),
            updatedAt = updatedAt.toString(),
        )

    private fun BackgroundArtifactDocument.toDto() =
        BackgroundArtifactDto(
            id = id.toHexString(),
            taskId = taskId.toHexString(),
            type = type,
            payload = payload,
            confidence = confidence,
            createdAt = createdAt.toString(),
        )

    private fun CoverageSnapshotDocument.toDto() =
        CoverageSnapshotDto(
            id = id.toHexString(),
            projectKey = projectKey,
            docs = docs,
            tasks = tasks,
            code = code,
            meetings = meetings,
            overall = overall,
            createdAt = createdAt.toString(),
        )
}
