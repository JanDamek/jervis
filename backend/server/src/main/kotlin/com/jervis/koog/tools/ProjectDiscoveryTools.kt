package com.jervis.koog.tools.project

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import com.jervis.domain.language.LanguageEnum
import com.jervis.entity.TaskDocument
import com.jervis.repository.ProjectRepository
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.Serializable
import mu.KotlinLogging

/**
 * Project and client context discovery tools.
 * Essential for "evidence-first" workflow: find project → services → repos → teams → policies.
 */
@LLMDescription("Project and client context discovery")
class ProjectDiscoveryTools(
    private val task: TaskDocument,
    private val projectRepository: ProjectRepository,
) : ToolSet {
    companion object {
        private val logger = KotlinLogging.logger {}
    }

    @Serializable
    data class ClientsProjects(
        val name: String,
        val description: String? = null,
        val communicationLanguageEnum: LanguageEnum = LanguageEnum.getDefault(),
        val jiraProjectKey: String? = null,
        val jiraBoardId: Long? = null,
        val confluenceSpaceKey: String? = null,
        val confluenceRootPageId: String? = null,
    )

    @Tool
    @LLMDescription("Get list of projects for the client.")
    suspend fun resolveProjectContext(): List<ClientsProjects> {
        logger.info { "resolveProjectContext | clientId=${task.clientId.value}" }

        val projects = projectRepository.findByClientId(task.clientId).toList()

        return projects.map { project ->
            ClientsProjects(
                project.name,
                project.description,
                project.communicationLanguageEnum,
                project.jiraProjectKey,
                project.jiraBoardId,
                project.confluenceSpaceKey,
                project.confluenceRootPageId,
            )
        }
    }
}
