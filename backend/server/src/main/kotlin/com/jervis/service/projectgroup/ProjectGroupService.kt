package com.jervis.service.projectgroup

import com.jervis.common.types.ClientId
import com.jervis.common.types.ProjectGroupId
import com.jervis.entity.ProjectGroupDocument
import com.jervis.repository.ProjectGroupRepository
import kotlinx.coroutines.flow.toList
import mu.KotlinLogging
import org.springframework.stereotype.Service

@Service
class ProjectGroupService(
    private val projectGroupRepository: ProjectGroupRepository,
) {
    companion object {
        private val logger = KotlinLogging.logger {}
    }

    suspend fun getAllGroups(): List<ProjectGroupDocument> =
        projectGroupRepository.findAll().toList()

    suspend fun listGroupsForClient(clientId: ClientId): List<ProjectGroupDocument> =
        projectGroupRepository.findByClientId(clientId).toList()

    suspend fun getGroupById(id: ProjectGroupId): ProjectGroupDocument =
        requireNotNull(getGroupByIdOrNull(id)) {
            "Project group not found with id: $id"
        }

    suspend fun getGroupByIdOrNull(id: ProjectGroupId): ProjectGroupDocument? =
        projectGroupRepository.getById(id)

    suspend fun saveGroup(group: ProjectGroupDocument): ProjectGroupDocument {
        val existing = getGroupByIdOrNull(group.id)
        val isNew = existing == null

        val merged = existing?.copy(
            name = group.name,
            description = group.description,
            connectionCapabilities = group.connectionCapabilities,
            resources = group.resources,
            resourceLinks = group.resourceLinks,
        ) ?: group

        val saved = projectGroupRepository.save(merged)

        if (isNew) {
            logger.info { "Created project group: ${saved.name}" }
        } else {
            logger.info { "Updated project group: ${saved.name}" }
        }

        return saved
    }

    suspend fun deleteGroup(id: ProjectGroupId) {
        val existing = getGroupByIdOrNull(id) ?: return
        projectGroupRepository.delete(existing)
        logger.info { "Deleted project group: ${existing.name}" }
    }
}
