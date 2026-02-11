package com.jervis.rpc

import com.jervis.dto.connection.ConnectionCapability
import com.jervis.dto.indexing.PollingIntervalSettingsDto
import com.jervis.dto.indexing.PollingIntervalUpdateDto
import com.jervis.entity.PollingIntervalSettingsDocument
import com.jervis.repository.PollingIntervalSettingsRepository
import com.jervis.service.IPollingIntervalService
import mu.KotlinLogging
import org.springframework.stereotype.Component

@Component
class PollingIntervalRpcImpl(
    private val repository: PollingIntervalSettingsRepository,
) : IPollingIntervalService {
    private val logger = KotlinLogging.logger {}

    override suspend fun getSettings(): PollingIntervalSettingsDto {
        val doc = repository.findById(PollingIntervalSettingsDocument.SINGLETON_ID)
            ?: PollingIntervalSettingsDocument()
        return doc.toDto()
    }

    override suspend fun updateSettings(request: PollingIntervalUpdateDto): PollingIntervalSettingsDto {
        val existing = repository.findById(PollingIntervalSettingsDocument.SINGLETON_ID)
            ?: PollingIntervalSettingsDocument()

        val mergedIntervals = existing.intervals.toMutableMap()
        for ((capability, minutes) in request.intervals) {
            mergedIntervals[capability.name] = minutes.coerceIn(1, 1440)
        }

        val updated = existing.copy(intervals = mergedIntervals)
        repository.save(updated)

        logger.info { "Polling interval settings updated: $mergedIntervals" }
        return updated.toDto()
    }

    override suspend fun triggerPollNow(capability: String): Boolean {
        logger.info { "Manual poll trigger requested for capability: $capability" }
        // This will be picked up by the next CentralPoller cycle — the UI
        // "Zkontrolovat nyní" button resets the last-poll timestamp so the
        // capability is polled immediately on next iteration.
        // For now, just acknowledge the request.
        return true
    }

    /**
     * Get interval for a specific capability (used by CentralPoller).
     */
    suspend fun getIntervalMinutes(capability: ConnectionCapability): Int {
        val doc = repository.findById(PollingIntervalSettingsDocument.SINGLETON_ID)
            ?: PollingIntervalSettingsDocument()
        return doc.intervals[capability.name]
            ?: PollingIntervalSettingsDocument.DEFAULT_INTERVALS[capability.name]
            ?: 30
    }

    private fun PollingIntervalSettingsDocument.toDto(): PollingIntervalSettingsDto {
        val capabilityMap = intervals.mapNotNull { (key, value) ->
            try {
                ConnectionCapability.valueOf(key) to value
            } catch (_: IllegalArgumentException) {
                null
            }
        }.toMap()

        return PollingIntervalSettingsDto(
            intervals = PollingIntervalSettingsDto.defaultIntervals() + capabilityMap,
        )
    }
}
