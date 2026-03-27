package com.jervis.preferences

import com.jervis.common.types.ProjectId
import com.jervis.dto.preferences.SystemConfigDto
import com.jervis.dto.preferences.UpdateSystemConfigRequest
import com.jervis.preferences.SystemConfigDocument
import com.jervis.project.ProjectRepository
import com.jervis.preferences.SystemConfigRepository
import com.jervis.service.preferences.ISystemConfigService
import mu.KotlinLogging
import org.bson.types.ObjectId
import org.springframework.stereotype.Component

@Component
class SystemConfigRpcImpl(
    private val repository: SystemConfigRepository,
    private val projectRepository: ProjectRepository,
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

        // Handle JERVIS Internal project assignment
        if (request.jervisInternalProjectId != null) {
            val newProjectId = ProjectId(ObjectId(request.jervisInternalProjectId))
            val oldProjectId = existing.jervisInternalProjectId?.let { ProjectId(it) }

            // Clear flag on old project if different
            if (oldProjectId != null && oldProjectId != newProjectId) {
                projectRepository.getById(oldProjectId)?.let {
                    projectRepository.save(it.copy(isJervisInternal = false))
                }
            }
            // Set flag on new project
            if (oldProjectId != newProjectId) {
                projectRepository.getById(newProjectId)?.let {
                    projectRepository.save(it.copy(isJervisInternal = true))
                }
            }
        }

        val updated = existing.copy(
            jervisInternalProjectId = request.jervisInternalProjectId
                ?.let { ObjectId(it) } ?: existing.jervisInternalProjectId,
        )

        repository.save(updated)
        logger.info { "System config updated: internalProject=${updated.jervisInternalProjectId}" }
        return updated.toDto()
    }

    /**
     * Get the raw document (used by internal services).
     */
    suspend fun getDocument(): SystemConfigDocument {
        return repository.findById(SystemConfigDocument.SINGLETON_ID)
            ?: SystemConfigDocument()
    }

    private fun SystemConfigDocument.toDto(): SystemConfigDto =
        SystemConfigDto(
            jervisInternalProjectId = jervisInternalProjectId?.toHexString(),
        )
}
