package com.jervis.project

import com.jervis.rpc.BaseRpcImpl
import com.jervis.common.types.ClientId
import com.jervis.common.types.ProjectId
import com.jervis.dto.project.MergeExecuteDto
import com.jervis.dto.project.MergePreviewDto
import com.jervis.dto.project.ProjectDto
import com.jervis.project.WorkspaceStatus
import com.jervis.project.toDocument
import com.jervis.project.toDto
import com.jervis.project.ProjectRepository
import com.jervis.infrastructure.error.ErrorLogService
import com.jervis.project.ProjectService
import com.jervis.service.project.IProjectService
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

    override suspend fun previewMerge(sourceProjectId: String, targetProjectId: String): MergePreviewDto =
        executeWithErrorHandling("previewMerge") {
            projectService.previewMerge(
                sourceId = ProjectId(ObjectId(sourceProjectId)),
                targetId = ProjectId(ObjectId(targetProjectId)),
            )
        }

    override suspend fun executeMerge(request: MergeExecuteDto): Boolean =
        executeWithErrorHandling("executeMerge") {
            projectService.executeMerge(request)
            true
        }
}
