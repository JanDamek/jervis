package com.jervis.service.background.admin

import com.jervis.domain.background.BackgroundTaskStatus
import com.jervis.entity.mongo.BackgroundSettingsDocument
import com.jervis.repository.mongo.BackgroundArtifactMongoRepository
import com.jervis.repository.mongo.BackgroundSettingsMongoRepository
import com.jervis.repository.mongo.BackgroundTaskMongoRepository
import kotlinx.coroutines.flow.count
import kotlinx.coroutines.flow.toList
import mu.KotlinLogging
import org.springframework.stereotype.Service
import java.time.Instant

/**
 * Administrative service for managing background engine operations.
 *
 * Provides utilities for:
 * - Resetting stuck tasks
 * - Cleaning up old artifacts
 * - Adjusting runtime settings
 * - System diagnostics
 */
@Service
class BackgroundEngineAdminService(
    private val taskRepository: BackgroundTaskMongoRepository,
    private val artifactRepository: BackgroundArtifactMongoRepository,
    private val settingsRepository: BackgroundSettingsMongoRepository,
) {
    private val logger = KotlinLogging.logger {}

    /**
     * Reset all RUNNING tasks to PENDING state.
     * Useful after server crash or restart.
     */
    suspend fun resetStuckTasks(): ResetResult {
        logger.info { "Resetting stuck RUNNING tasks to PENDING" }

        val runningTasks =
            taskRepository
                .findByStatusOrderByCreatedAtDesc(BackgroundTaskStatus.RUNNING.name)
                .toList()

        var resetCount = 0
        runningTasks.forEach { task ->
            try {
                taskRepository.save(
                    task.copy(
                        status = BackgroundTaskStatus.PENDING.name,
                        notes = "Reset from RUNNING to PENDING by admin",
                        updatedAt = Instant.now(),
                    ),
                )
                resetCount++
            } catch (e: Exception) {
                logger.error(e) { "Failed to reset task ${task.id}" }
            }
        }

        logger.info { "Reset $resetCount tasks from RUNNING to PENDING" }

        return ResetResult(
            tasksReset = resetCount,
            totalRunningBefore = runningTasks.size,
        )
    }

    /**
     * Reset all PARTIAL tasks to PENDING state.
     * Useful to retry interrupted tasks.
     */
    suspend fun retryPartialTasks(): ResetResult {
        logger.info { "Retrying PARTIAL tasks" }

        val partialTasks =
            taskRepository
                .findByStatusOrderByCreatedAtDesc(BackgroundTaskStatus.PARTIAL.name)
                .toList()

        var resetCount = 0
        partialTasks.forEach { task ->
            try {
                taskRepository.save(
                    task.copy(
                        status = BackgroundTaskStatus.PENDING.name,
                        notes = "Retrying PARTIAL task by admin",
                        updatedAt = Instant.now(),
                    ),
                )
                resetCount++
            } catch (e: Exception) {
                logger.error(e) { "Failed to reset task ${task.id}" }
            }
        }

        logger.info { "Reset $resetCount tasks from PARTIAL to PENDING" }

        return ResetResult(
            tasksReset = resetCount,
            totalRunningBefore = partialTasks.size,
        )
    }

    /**
     * Clean up old completed tasks and their artifacts.
     * Keeps tasks from last N days.
     */
    suspend fun cleanupOldData(keepDays: Int = 30): CleanupResult {
        logger.info { "Cleaning up data older than $keepDays days" }

        val cutoffDate = Instant.now().minusSeconds(keepDays * 24 * 60 * 60L)

        val completedTasks =
            taskRepository
                .findByStatusOrderByCreatedAtDesc(BackgroundTaskStatus.COMPLETED.name)
                .toList()
                .filter { it.createdAt.isBefore(cutoffDate) }

        var tasksDeleted = 0
        var artifactsDeleted = 0

        completedTasks.forEach { task ->
            try {
                val artifacts =
                    artifactRepository
                        .findByTaskIdOrderByCreatedAtDesc(task.id)
                        .toList()

                artifacts.forEach { artifact ->
                    artifactRepository.deleteById(artifact.id)
                    artifactsDeleted++
                }

                taskRepository.deleteById(task.id)
                tasksDeleted++
            } catch (e: Exception) {
                logger.error(e) { "Failed to cleanup task ${task.id}" }
            }
        }

        logger.info { "Cleaned up $tasksDeleted tasks and $artifactsDeleted artifacts" }

        return CleanupResult(
            tasksDeleted = tasksDeleted,
            artifactsDeleted = artifactsDeleted,
        )
    }

    /**
     * Get comprehensive system diagnostics.
     */
    suspend fun getDiagnostics(): SystemDiagnostics {
        val pending = taskRepository.countByStatus(BackgroundTaskStatus.PENDING.name)
        val running = taskRepository.countByStatus(BackgroundTaskStatus.RUNNING.name)
        val partial = taskRepository.countByStatus(BackgroundTaskStatus.PARTIAL.name)
        val completed = taskRepository.countByStatus(BackgroundTaskStatus.COMPLETED.name)
        val failed = taskRepository.countByStatus(BackgroundTaskStatus.SUSPENDED.name)

        val totalArtifacts =
            artifactRepository
                .findAll()
                .count()

        val settings = settingsRepository.findById("background_engine")

        return SystemDiagnostics(
            tasks =
                TaskDiagnostics(
                    pending = pending,
                    running = running,
                    partial = partial,
                    completed = completed,
                    failed = failed,
                ),
            artifacts =
                ArtifactDiagnostics(
                    total = totalArtifacts,
                ),
            settings = settings,
        )
    }

    /**
     * Update runtime settings without restart.
     */
    suspend fun updateSettings(update: SettingsUpdate): BackgroundSettingsDocument {
        val current =
            settingsRepository.findById("background_engine")
                ?: BackgroundSettingsDocument()

        val updated =
            current.copy(
                idleThresholdSeconds = update.idleThresholdSeconds ?: current.idleThresholdSeconds,
                chunkTokenLimit = update.chunkTokenLimit ?: current.chunkTokenLimit,
                chunkTimeoutSeconds = update.chunkTimeoutSeconds ?: current.chunkTimeoutSeconds,
                maxCpuBgTasks = update.maxCpuBgTasks ?: current.maxCpuBgTasks,
            )

        settingsRepository.save(updated)
        logger.info { "Updated background engine settings: $updated" }

        return updated
    }

    data class ResetResult(
        val tasksReset: Int,
        val totalRunningBefore: Int,
    )

    data class CleanupResult(
        val tasksDeleted: Int,
        val artifactsDeleted: Int,
    )

    data class SystemDiagnostics(
        val tasks: TaskDiagnostics,
        val artifacts: ArtifactDiagnostics,
        val settings: BackgroundSettingsDocument?,
    )

    data class TaskDiagnostics(
        val pending: Long,
        val running: Long,
        val partial: Long,
        val completed: Long,
        val failed: Long,
    )

    data class ArtifactDiagnostics(
        val total: Int,
    )

    data class SettingsUpdate(
        val idleThresholdSeconds: Long? = null,
        val chunkTokenLimit: Int? = null,
        val chunkTimeoutSeconds: Long? = null,
        val maxCpuBgTasks: Int? = null,
    )
}
