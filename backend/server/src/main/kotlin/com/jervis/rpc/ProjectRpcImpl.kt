package com.jervis.rpc

import com.jervis.common.types.ClientId
import com.jervis.common.types.ProjectId
import com.jervis.dto.ProjectDto
import com.jervis.entity.WorkspaceStatus
import com.jervis.mapper.toDocument
import com.jervis.mapper.toDto
import com.jervis.repository.ProjectRepository
import com.jervis.service.IProjectService
import com.jervis.service.error.ErrorLogService
import com.jervis.service.project.ProjectService
import org.bson.types.ObjectId
import org.springframework.stereotype.Component

@Component
class ProjectRpcImpl(
    private val projectService: ProjectService,
    private val projectRepository: ProjectRepository,
    errorLogService: ErrorLogService,
) : BaseRpcImpl(errorLogService),
    IProjectService {
    override suspend fun listProjectsForClient(clientId: String): List<ProjectDto> =
        executeWithErrorHandling("listProjectsForClient") {
            projectService.listProjectsForClient(ClientId(ObjectId(clientId))).map { it.toDto() }
        }

    override suspend fun getAllProjects(): List<ProjectDto> =
        executeWithErrorHandling("getAllProjects") {
            projectService.getAllProjects().map { it.toDto() }
        }

    override suspend fun saveProject(project: ProjectDto): ProjectDto =
        executeWithErrorHandling("saveProject") {
            projectService.saveProject(project.toDocument())
        }

    override suspend fun updateProject(
        id: String,
        project: ProjectDto,
    ): ProjectDto =
        executeWithErrorHandling("updateProject") {
            projectService.saveProject(project.copy(id = id).toDocument())
        }

    override suspend fun deleteProject(project: ProjectDto) {
        executeWithErrorHandling("deleteProject") {
            projectService.deleteProject(project)
        }
    }

    override suspend fun getProjectByName(name: String?): ProjectDto =
        executeWithErrorHandling("getProjectByName") {
            projectService.getProjectByName(name).toDto()
        }

    override suspend fun retryWorkspace(projectId: String): Boolean =
        executeWithErrorHandling("retryWorkspace") {
            val project = projectService.getProjectByIdOrNull(ProjectId(ObjectId(projectId)))
                ?: return@executeWithErrorHandling false
            if (project.workspaceStatus == null || !project.workspaceStatus.isCloneFailed) return@executeWithErrorHandling false
            projectRepository.save(
                project.copy(
                    workspaceStatus = null,
                    workspaceRetryCount = 0,
                    nextWorkspaceRetryAt = null,
                    lastWorkspaceError = null,
                ),
            )
            true
        }
}
