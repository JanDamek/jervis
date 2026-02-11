package com.jervis.service.environment

import com.jervis.common.types.ClientId
import com.jervis.common.types.EnvironmentId
import com.jervis.common.types.ProjectGroupId
import com.jervis.common.types.ProjectId
import com.jervis.entity.EnvironmentDocument
import com.jervis.repository.EnvironmentRepository
import com.jervis.repository.ProjectRepository
import kotlinx.coroutines.flow.toList
import mu.KotlinLogging
import org.springframework.stereotype.Service

@Service
class EnvironmentService(
    private val environmentRepository: EnvironmentRepository,
    private val projectRepository: ProjectRepository,
) {
    companion object {
        private val logger = KotlinLogging.logger {}
    }

    suspend fun getAllEnvironments(): List<EnvironmentDocument> =
        environmentRepository.findAll().toList()

    suspend fun listEnvironmentsForClient(clientId: ClientId): List<EnvironmentDocument> =
        environmentRepository.findByClientId(clientId).toList()

    suspend fun getEnvironmentById(id: EnvironmentId): EnvironmentDocument =
        requireNotNull(getEnvironmentByIdOrNull(id)) {
            "Environment not found with id: $id"
        }

    suspend fun getEnvironmentByIdOrNull(id: EnvironmentId): EnvironmentDocument? =
        environmentRepository.getById(id)

    suspend fun saveEnvironment(env: EnvironmentDocument): EnvironmentDocument {
        val existing = getEnvironmentByIdOrNull(env.id)
        val isNew = existing == null

        val merged = existing?.copy(
            name = env.name,
            description = env.description,
            namespace = env.namespace,
            groupId = env.groupId,
            projectId = env.projectId,
            components = env.components,
            componentLinks = env.componentLinks,
            propertyMappings = env.propertyMappings,
            agentInstructions = env.agentInstructions,
        ) ?: env

        val saved = environmentRepository.save(merged)

        if (isNew) {
            logger.info { "Created environment: ${saved.name} (ns=${saved.namespace})" }
        } else {
            logger.info { "Updated environment: ${saved.name} (ns=${saved.namespace})" }
        }

        return saved
    }

    suspend fun deleteEnvironment(id: EnvironmentId) {
        val existing = getEnvironmentByIdOrNull(id) ?: return
        environmentRepository.delete(existing)
        logger.info { "Deleted environment: ${existing.name}" }
    }

    /**
     * Resolve the effective environment for a project.
     * Follows inheritance: project -> group -> client.
     */
    suspend fun resolveEnvironmentForProject(projectId: ProjectId): EnvironmentDocument? {
        // 1. Check project-level environment
        val projectEnv = environmentRepository.findByProjectId(projectId)
        if (projectEnv != null) return projectEnv

        // 2. Check group-level environment (if project has a group)
        val project = projectRepository.getById(projectId) ?: return null

        if (project.groupId != null) {
            val groupEnv = environmentRepository.findByGroupId(project.groupId)
            if (groupEnv != null) return groupEnv
        }

        // 3. Check client-level environment
        return environmentRepository.findByClientIdAndGroupIdIsNullAndProjectIdIsNull(project.clientId)
    }

    /**
     * Update environment state.
     */
    suspend fun updateState(id: EnvironmentId, state: com.jervis.entity.EnvironmentState): EnvironmentDocument {
        val existing = getEnvironmentById(id)
        val updated = existing.copy(state = state)
        return environmentRepository.save(updated)
    }

    /**
     * Update resolved property values after provisioning.
     */
    suspend fun updateResolvedValues(
        id: EnvironmentId,
        resolvedMappings: List<com.jervis.entity.PropertyMapping>,
    ): EnvironmentDocument {
        val existing = getEnvironmentById(id)
        val updated = existing.copy(propertyMappings = resolvedMappings)
        return environmentRepository.save(updated)
    }
}
