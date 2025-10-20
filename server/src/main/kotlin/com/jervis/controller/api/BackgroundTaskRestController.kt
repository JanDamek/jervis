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
import com.jervis.service.IBackgroundTaskService
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import mu.KotlinLogging
import org.bson.types.ObjectId
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * REST API for managing background cognitive tasks.
 */
@RestController
class BackgroundTaskRestController(
    private val taskRepository: BackgroundTaskMongoRepository,
    private val artifactRepository: BackgroundArtifactMongoRepository,
    private val coverageRepository: CoverageSnapshotMongoRepository,
) : IBackgroundTaskService {
    private val logger = KotlinLogging.logger {}

    override suspend fun listTasks(
        @RequestParam(required = false) status: String?,
    ): List<Map<String, Any>> {
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

        return tasks.map { it.toMap() }.toList()
    }

    override suspend fun getTask(
        @PathVariable taskId: String,
    ): Map<String, Any>? {
        val task = taskRepository.findById(ObjectId(taskId))
        return task?.toMap()
    }

    override suspend fun createTask(
        @RequestBody request: Map<String, Any>,
    ): Map<String, Any> {
        val task =
            BackgroundTask(
                taskType = BackgroundTaskType.valueOf(request["taskType"] as String),
                targetRef =
                    TargetRef(
                        type = TargetRefType.valueOf(request["targetRefType"] as String),
                        id = request["targetRefId"] as String,
                    ),
                priority = (request["priority"] as? Int) ?: 3,
                status = BackgroundTaskStatus.PENDING,
                labels = (request["labels"] as? List<*>)?.filterIsInstance<String>() ?: emptyList(),
                notes = request["notes"] as? String,
            )

        val document = BackgroundTaskDocument.fromDomain(task)
        val saved = taskRepository.save(document)

        logger.info { "Created background task: ${saved.id} (${saved.taskType})" }

        return saved.toMap()
    }

    override suspend fun listArtifacts(
        @RequestParam(required = false) taskId: String?,
        @RequestParam(required = false) type: String?,
    ): List<Map<String, Any>> {
        val artifacts =
            when {
                taskId != null -> artifactRepository.findByTaskIdOrderByCreatedAtDesc(ObjectId(taskId))
                type != null -> artifactRepository.findByTypeOrderByConfidenceDesc(type)
                else -> throw IllegalArgumentException("Either taskId or type must be specified")
            }

        return artifacts.map { it.toMap() }.toList()
    }

    override suspend fun getCoverage(
        @PathVariable projectKey: String,
    ): Map<String, Any>? {
        val snapshot = coverageRepository.findFirstByProjectKeyOrderByCreatedAtDesc(projectKey)
        return snapshot?.toMap()
    }

    override suspend fun listCoverage(
        @RequestParam projectKey: String,
        @RequestParam(defaultValue = "10") limit: Int,
    ): List<Map<String, Any>> {
        val snapshots =
            coverageRepository
                .findByProjectKeyOrderByCreatedAtDesc(projectKey)
                .toList()
                .take(limit)

        return snapshots.map { it.toMap() }
    }

    override suspend fun getStats(): Map<String, Any> {
        val pending = taskRepository.countByStatus(BackgroundTaskStatus.PENDING.name)
        val running = taskRepository.countByStatus(BackgroundTaskStatus.RUNNING.name)
        val partial = taskRepository.countByStatus(BackgroundTaskStatus.PARTIAL.name)
        val completed = taskRepository.countByStatus(BackgroundTaskStatus.COMPLETED.name)
        val failed = taskRepository.countByStatus(BackgroundTaskStatus.SUSPENDED.name)

        return mapOf(
            "pendingTasks" to pending,
            "runningTasks" to running,
            "partialTasks" to partial,
            "completedTasks" to completed,
            "failedTasks" to failed,
        )
    }

    private fun BackgroundTaskDocument.toMap(): Map<String, Any> =
        mapOf(
            "id" to id.toHexString(),
            "taskType" to taskType,
            "targetRef" to targetRef,
            "priority" to priority,
            "status" to status,
            "progress" to progress,
            "retryCount" to retryCount,
            "labels" to labels,
            "notes" to (notes ?: ""),
            "createdAt" to createdAt.toString(),
            "updatedAt" to updatedAt.toString(),
        )

    private fun BackgroundArtifactDocument.toMap(): Map<String, Any> =
        mapOf(
            "id" to id.toHexString(),
            "taskId" to taskId.toHexString(),
            "type" to type,
            "payload" to payload,
            "confidence" to confidence,
            "createdAt" to createdAt.toString(),
        )

    private fun CoverageSnapshotDocument.toMap(): Map<String, Any> =
        mapOf(
            "id" to id.toHexString(),
            "projectKey" to projectKey,
            "docs" to docs,
            "tasks" to tasks,
            "code" to code,
            "meetings" to meetings,
            "overall" to overall,
            "createdAt" to createdAt.toString(),
        )
}
