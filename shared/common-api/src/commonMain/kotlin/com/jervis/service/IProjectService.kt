package com.jervis.service

import com.jervis.dto.ProjectDto
import de.jensklingenberg.ktorfit.http.*

/**
 * Project Service API
 * Ktorfit will auto-generate client implementation for Desktop/Mobile
 * Server implements this interface manually in Controller
 */
interface IProjectService {

    @GET("api/projects")
    suspend fun getAllProjects(): List<ProjectDto>

    @POST("api/projects")
    suspend fun saveProject(@Body project: ProjectDto): ProjectDto

    @PUT("api/projects/{id}")
    suspend fun updateProject(
        @Path id: String,
        @Body project: ProjectDto,
    ): ProjectDto

    @DELETE("api/projects")
    suspend fun deleteProject(@Body project: ProjectDto)

    @GET("api/projects/by-name")
    suspend fun getProjectByName(@Query name: String?): ProjectDto
}
