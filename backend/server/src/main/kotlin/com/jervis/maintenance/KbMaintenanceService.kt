package com.jervis.maintenance

import com.jervis.infrastructure.llm.KnowledgeServiceRestClient
import com.jervis.dto.maintenance.IdleTaskType
import com.jervis.maintenance.MaintenanceStateDocument
import com.jervis.maintenance.MaintenanceType
import com.jervis.client.ClientRepository
import com.jervis.maintenance.MaintenanceStateRepository
import kotlinx.coroutines.flow.toList
import mu.KotlinLogging
import org.springframework.stereotype.Service
import java.time.Instant

/**
 * KB Maintenance Service — checkpoint-based, preemptible maintenance pipeline.
 *
 * Manages long-running KB maintenance tasks that run during GPU idle time.
 * Each task processes items in batches, saving progress after each batch.
 * When preempted (FG/BG work arrives), resumes from the last checkpoint.
 *
 * Flow:
 * 1. pickNextWork() — finds the highest-priority task that needs work
 * 2. processBatch() — processes one batch (e.g., 100 nodes), saves cursor
 * 3. Caller checks if preempted → if not, calls processBatch() again
 * 4. When all items processed → marks cycle complete, starts cooldown
 */
@Service
class KbMaintenanceService(
    private val maintenanceStateRepository: MaintenanceStateRepository,
    private val clientRepository: ClientRepository,
    private val knowledgeClient: KnowledgeServiceRestClient,
    private val idleTaskRegistry: IdleTaskRegistry,
) {
    private val logger = KotlinLogging.logger {}

    companion object {
        const val BATCH_SIZE = 100
        const val COOLDOWN_MINUTES = 30
    }

    /**
     * Pick the next maintenance work to do.
     * Returns the maintenance state (with cursor for resume) or null if nothing to do.
     */
    suspend fun pickNextWork(): MaintenanceStateDocument? {
        val allClients = try {
            clientRepository.findAll().toList().filter { !it.archived }
        } catch (_: Exception) {
            return null
        }
        if (allClients.isEmpty()) return null

        val now = Instant.now()

        // Check each task type in priority order
        val pipeline = idleTaskRegistry.getAllTasks()
            .filter { it.enabled }
            .sortedByDescending { it.priority }

        for (config in pipeline) {
            val mType = idleTaskTypeToMaintenanceType(config.type) ?: continue

            for (client in allClients) {
                val clientId = client.id?.toString() ?: continue
                val stateId = "${mType.name.lowercase()}:$clientId"
                val state = maintenanceStateRepository.findById(stateId)

                if (state == null) {
                    // Never run for this client → create and return
                    val newState = MaintenanceStateDocument(
                        id = stateId,
                        maintenanceType = mType,
                        clientId = clientId,
                        startedAt = now,
                        cooldownMinutes = COOLDOWN_MINUTES,
                    )
                    maintenanceStateRepository.save(newState)
                    logger.info { "MAINTENANCE_START: ${mType.name} for client $clientId (first run)" }
                    return newState
                }

                // In progress (has cursor, not completed) → resume
                if (state.cursor != null && state.completedAt == null) {
                    logger.info { "MAINTENANCE_RESUME: ${mType.name} for client $clientId (cursor=${state.cursor}, processed=${state.processedCount})" }
                    return state
                }

                // Completed but cooldown expired → start new cycle
                val lastComplete = state.completedAt ?: state.lastFullCycleAt
                if (lastComplete != null) {
                    val cooldownEnd = lastComplete.plusSeconds(config.intervalHours * 3600L)
                    if (now.isAfter(cooldownEnd)) {
                        // Reset for new cycle
                        val resetState = state.copy(
                            cursor = null,
                            processedCount = 0,
                            totalCount = 0,
                            findingsCount = 0,
                            fixedCount = 0,
                            startedAt = now,
                            completedAt = null,
                            lastError = null,
                        )
                        maintenanceStateRepository.save(resetState)
                        logger.info { "MAINTENANCE_START: ${mType.name} for client $clientId (cooldown expired)" }
                        return resetState
                    }
                }
                // Still in cooldown → try next client for this type
            }
            // All clients done for this type → try next type
        }

        logger.debug { "MAINTENANCE: All tasks in cooldown, nothing to do" }
        return null
    }

    /**
     * Process one batch of maintenance work.
     * Returns updated state with new cursor, or state with completedAt if done.
     */
    suspend fun processBatch(state: MaintenanceStateDocument): MaintenanceStateDocument {
        return try {
            val result = knowledgeClient.runMaintenanceBatch(
                maintenanceType = state.maintenanceType.name.lowercase(),
                clientId = state.clientId,
                cursor = state.cursor,
                batchSize = BATCH_SIZE,
            )

            if (result == null) {
                // KB service error — save error and move on
                val errState = state.copy(lastError = "KB service returned null", completedAt = Instant.now())
                maintenanceStateRepository.save(errState)
                return errState
            }

            val now = Instant.now()
            val updatedState = if (result.completed) {
                // Batch processing complete for this client
                state.copy(
                    cursor = null,
                    processedCount = state.processedCount + result.processed,
                    totalCount = result.totalEstimate,
                    findingsCount = state.findingsCount + result.findings,
                    fixedCount = state.fixedCount + result.fixed,
                    completedAt = now,
                    lastFullCycleAt = now,
                    lastError = null,
                )
            } else {
                // More work to do — save cursor for resume
                state.copy(
                    cursor = result.nextCursor,
                    processedCount = state.processedCount + result.processed,
                    totalCount = result.totalEstimate,
                    findingsCount = state.findingsCount + result.findings,
                    fixedCount = state.fixedCount + result.fixed,
                    lastError = null,
                )
            }

            maintenanceStateRepository.save(updatedState)

            if (result.completed) {
                logger.info {
                    "MAINTENANCE_COMPLETE: ${state.maintenanceType} for client ${state.clientId} — " +
                        "processed=${updatedState.processedCount} findings=${updatedState.findingsCount} fixed=${updatedState.fixedCount}"
                }
            } else {
                logger.debug {
                    "MAINTENANCE_BATCH: ${state.maintenanceType} for client ${state.clientId} — " +
                        "processed=${updatedState.processedCount}/${result.totalEstimate} cursor=${result.nextCursor}"
                }
            }

            updatedState
        } catch (e: Exception) {
            logger.error(e) { "MAINTENANCE_ERROR: ${state.maintenanceType} for client ${state.clientId}" }
            val errState = state.copy(lastError = e.message)
            maintenanceStateRepository.save(errState)
            errState
        }
    }

    /**
     * Get maintenance status for UI display.
     */
    suspend fun getMaintenanceStatus(): List<MaintenanceStateDocument> {
        return maintenanceStateRepository.findAll().toList()
    }

    private fun idleTaskTypeToMaintenanceType(type: IdleTaskType): MaintenanceType? = when (type) {
        IdleTaskType.KB_DEDUP -> MaintenanceType.DEDUP
        IdleTaskType.KB_ORPHAN_CLEANUP -> MaintenanceType.ORPHAN_CLEANUP
        IdleTaskType.KB_CONSISTENCY_CHECK -> MaintenanceType.CONSISTENCY_CHECK
        IdleTaskType.THOUGHT_DECAY -> MaintenanceType.THOUGHT_DECAY
        IdleTaskType.THOUGHT_MERGE -> MaintenanceType.THOUGHT_MERGE
        IdleTaskType.EMBEDDING_QUALITY -> MaintenanceType.EMBEDDING_QUALITY
    }
}
