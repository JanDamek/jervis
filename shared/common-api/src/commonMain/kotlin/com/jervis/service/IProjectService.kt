package com.jervis.service

import com.jervis.dto.MergeExecuteDto
import com.jervis.dto.MergePreviewDto
import com.jervis.dto.ProjectDto
import kotlinx.rpc.annotations.Rpc

/**
 * Project Service API
 * kotlinx-rpc will auto-generate client implementation for Desktop/Mobile
 */
@Rpc
interface IProjectService {
    suspend fun listProjectsForClient(clientId: String): List<ProjectDto>

    suspend fun getAllProjects(): List<ProjectDto>

    suspend fun saveProject(project: ProjectDto): ProjectDto

    suspend fun updateProject(
        id: String,
        project: ProjectDto,
    ): ProjectDto

    suspend fun deleteProject(project: ProjectDto)

    suspend fun getProjectByName(name: String?): ProjectDto

    suspend fun retryWorkspace(projectId: String): Boolean

    /** Preview merge: detect conflicts between source and target project. */
    suspend fun previewMerge(sourceProjectId: String, targetProjectId: String): MergePreviewDto

    /** Execute merge with conflict resolutions. Moves all data, then deletes source. */
    suspend fun executeMerge(request: MergeExecuteDto): Boolean
}
