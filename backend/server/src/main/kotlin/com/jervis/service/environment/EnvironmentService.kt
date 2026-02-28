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

        private val NAMESPACE_REGEX = Regex("^[a-z0-9]([a-z0-9-]*[a-z0-9])?\$")
        private val RESERVED_NAMESPACES = setOf(
            "default", "kube-system", "kube-public", "kube-node-lease",
            "jervis", "monitoring", "logging",
        )
        private val RESERVED_PREFIXES = listOf("kube-", "openshift-", "istio-")

        fun validateNamespace(namespace: String) {
            require(namespace.length in 1..63) {
                "Namespace musí mít 1-63 znaků (má ${namespace.length})"
            }
            require(NAMESPACE_REGEX.matches(namespace)) {
                "Namespace smí obsahovat pouze malá písmena, čísla a pomlčky (a-z, 0-9, -)"
            }
            require(namespace !in RESERVED_NAMESPACES) {
                "Namespace '$namespace' je rezervovaný systémový namespace"
            }
            require(RESERVED_PREFIXES.none { namespace.startsWith(it) }) {
                "Namespace nesmí začínat na ${RESERVED_PREFIXES.joinToString(", ")}"
            }
        }
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
        validateNamespace(env.namespace)

        val existing = getEnvironmentByIdOrNull(env.id)
        val isNew = existing == null

        val merged = existing?.copy(
            name = env.name,
            description = env.description,
            tier = env.tier,
            namespace = env.namespace,
            groupId = env.groupId,
            projectId = env.projectId,
            components = env.components,
            componentLinks = env.componentLinks,
            propertyMappings = env.propertyMappings,
            agentInstructions = env.agentInstructions,
            storageSizeGi = env.storageSizeGi,
            yamlManifests = env.yamlManifests,
            // Preserve state and clientId — never overwrite via UI save
            state = existing.state,
            clientId = existing.clientId,
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
     * Clone an environment to a new scope (different client, group, or project).
     * Creates a fresh copy with PENDING state, new namespace, and no stored manifests.
     */
    suspend fun cloneEnvironment(
        sourceId: EnvironmentId,
        newName: String,
        newNamespace: String,
        targetClientId: ClientId? = null,
        targetGroupId: ProjectGroupId? = null,
        targetProjectId: ProjectId? = null,
        newTier: com.jervis.entity.EnvironmentTier? = null,
    ): EnvironmentDocument {
        val source = getEnvironmentById(sourceId)
        val clone = source.copy(
            id = com.jervis.common.types.EnvironmentId.generate(),
            clientId = targetClientId ?: source.clientId,
            groupId = targetGroupId ?: source.groupId,
            projectId = targetProjectId ?: source.projectId,
            name = newName,
            namespace = newNamespace,
            tier = newTier ?: source.tier,
            state = com.jervis.entity.EnvironmentState.PENDING,
            // Reset runtime state — clone is a fresh definition
            yamlManifests = emptyMap(),
            components = source.components.map { it.copy(componentState = com.jervis.entity.ComponentState.PENDING) },
            propertyMappings = source.propertyMappings.map { it.copy(resolvedValue = null) },
        )
        val saved = environmentRepository.save(clone)
        logger.info { "Cloned environment: ${source.name} -> ${saved.name} (ns=${saved.namespace})" }
        return saved
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
