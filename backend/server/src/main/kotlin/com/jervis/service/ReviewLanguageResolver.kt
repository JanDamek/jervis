package com.jervis.service

import com.jervis.common.types.ClientId
import com.jervis.common.types.ProjectId
import com.jervis.service.client.ClientService
import com.jervis.service.project.ProjectService
import com.jervis.service.projectgroup.ProjectGroupService
import mu.KotlinLogging
import org.springframework.stereotype.Service

/**
 * Resolves review language with hierarchical override:
 * project → group → client → default ("English").
 */
@Service
class ReviewLanguageResolver(
    private val clientService: ClientService,
    private val projectService: ProjectService,
    private val projectGroupService: ProjectGroupService,
) {
    companion object {
        private val logger = KotlinLogging.logger {}
        const val DEFAULT_LANGUAGE = "English"
    }

    /**
     * Resolve effective review language for a project/client combination.
     * Override hierarchy: project > group > client > default.
     */
    suspend fun resolve(clientId: ClientId?, projectId: ProjectId?): String {
        // 1. Project-level override (most specific)
        val project = projectId?.let {
            try { projectService.getProjectByIdOrNull(it) } catch (_: Exception) { null }
        }
        if (project?.reviewLanguage != null) {
            logger.debug { "ReviewLanguage: using project override '${project.reviewLanguage}' for project=${project.id}" }
            return project.reviewLanguage
        }

        // 2. Group-level override
        val group = project?.groupId?.let {
            try { projectGroupService.getGroupByIdOrNull(it) } catch (_: Exception) { null }
        }
        if (group?.reviewLanguage != null) {
            logger.debug { "ReviewLanguage: using group override '${group.reviewLanguage}' for group=${group.id}" }
            return group.reviewLanguage
        }

        // 3. Client-level default
        val client = clientId?.let {
            try { clientService.getClientByIdOrNull(it) } catch (_: Exception) { null }
        }
        if (client != null) {
            logger.debug { "ReviewLanguage: using client value '${client.reviewLanguage}' for client=${client.id}" }
            return client.reviewLanguage
        }

        // 4. System default
        return DEFAULT_LANGUAGE
    }
}
