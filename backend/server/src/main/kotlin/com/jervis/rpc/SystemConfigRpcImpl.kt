package com.jervis.rpc

import com.jervis.dto.SystemConfigDto
import com.jervis.dto.UpdateSystemConfigRequest
import com.jervis.entity.SystemConfigDocument
import com.jervis.repository.SystemConfigRepository
import com.jervis.service.ISystemConfigService
import mu.KotlinLogging
import org.bson.types.ObjectId
import org.springframework.stereotype.Component

@Component
class SystemConfigRpcImpl(
    private val repository: SystemConfigRepository,
) : ISystemConfigService {
    private val logger = KotlinLogging.logger {}

    override suspend fun getSystemConfig(): SystemConfigDto {
        val doc = repository.findById(SystemConfigDocument.SINGLETON_ID)
            ?: SystemConfigDocument()
        return doc.toDto()
    }

    override suspend fun updateSystemConfig(request: UpdateSystemConfigRequest): SystemConfigDto {
        val existing = repository.findById(SystemConfigDocument.SINGLETON_ID)
            ?: SystemConfigDocument()

        val updated = existing.copy(
            brainBugtrackerConnectionId = request.brainBugtrackerConnectionId
                ?.let { ObjectId(it) } ?: existing.brainBugtrackerConnectionId,
            brainBugtrackerProjectKey = request.brainBugtrackerProjectKey
                ?: existing.brainBugtrackerProjectKey,
            brainWikiConnectionId = request.brainWikiConnectionId
                ?.let { ObjectId(it) } ?: existing.brainWikiConnectionId,
            brainWikiSpaceKey = request.brainWikiSpaceKey
                ?: existing.brainWikiSpaceKey,
            brainWikiRootPageId = request.brainWikiRootPageId
                ?: existing.brainWikiRootPageId,
        )

        repository.save(updated)
        logger.info { "System config updated: bugtracker=${updated.brainBugtrackerConnectionId}, wiki=${updated.brainWikiConnectionId}" }
        return updated.toDto()
    }

    /**
     * Get the raw document (used by internal services like BrainWriteService).
     */
    suspend fun getDocument(): SystemConfigDocument {
        return repository.findById(SystemConfigDocument.SINGLETON_ID)
            ?: SystemConfigDocument()
    }

    private fun SystemConfigDocument.toDto(): SystemConfigDto =
        SystemConfigDto(
            brainBugtrackerConnectionId = brainBugtrackerConnectionId?.toHexString(),
            brainBugtrackerProjectKey = brainBugtrackerProjectKey,
            brainWikiConnectionId = brainWikiConnectionId?.toHexString(),
            brainWikiSpaceKey = brainWikiSpaceKey,
            brainWikiRootPageId = brainWikiRootPageId,
        )
}
