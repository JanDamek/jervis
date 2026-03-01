package com.jervis.service

import com.jervis.common.types.ClientId
import com.jervis.common.types.ProjectGroupId
import com.jervis.common.types.ProjectId
import com.jervis.entity.CloudModelPolicy
import com.jervis.service.client.ClientService
import com.jervis.service.project.ProjectService
import com.jervis.service.projectgroup.ProjectGroupService
import mu.KotlinLogging
import org.springframework.stereotype.Service

/**
 * Resolves CloudModelPolicy with hierarchical override:
 * project → group → client → default (FREE).
 */
@Service
class CloudModelPolicyResolver(
    private val clientService: ClientService,
    private val projectService: ProjectService,
    private val projectGroupService: ProjectGroupService,
) {
    companion object {
        private val logger = KotlinLogging.logger {}
    }

    /**
     * Resolve effective CloudModelPolicy for a project/client combination.
     * Override hierarchy: project > group > client > default.
     */
    suspend fun resolve(clientId: ClientId?, projectId: ProjectId?): CloudModelPolicy {
        // 1. Project-level override (most specific)
        val project = projectId?.let {
            try { projectService.getProjectByIdOrNull(it) } catch (_: Exception) { null }
        }
        if (project?.cloudModelPolicy != null) {
            logger.debug { "CloudModelPolicy: using project override for project=${project.id}" }
            return project.cloudModelPolicy
        }

        // 2. Group-level override
        val group = project?.groupId?.let {
            try { projectGroupService.getGroupByIdOrNull(it) } catch (_: Exception) { null }
        }
        if (group?.cloudModelPolicy != null) {
            logger.debug { "CloudModelPolicy: using group override for group=${group.id}" }
            return group.cloudModelPolicy
        }

        // 3. Client-level policy
        val client = clientId?.let {
            try { clientService.getClientByIdOrNull(it) } catch (_: Exception) { null }
        }
        if (client != null) {
            logger.debug { "CloudModelPolicy: using client policy for client=${client.id}" }
            return client.cloudModelPolicy ?: CloudModelPolicy()
        }

        // 4. Default
        return CloudModelPolicy()
    }
}
