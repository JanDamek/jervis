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

    @GET("api/projects/default")
    suspend fun getDefaultProject(): ProjectDto?

    @PUT("api/projects/active")
    suspend fun setActiveProject(@Body project: ProjectDto)

    @PUT("api/projects/default")
    suspend fun setDefaultProject(@Body project: ProjectDto)

    @POST("api/projects")
    suspend fun saveProject(
        @Body project: ProjectDto,
        @Query makeDefault: Boolean = false
    ): ProjectDto

    @DELETE("api/projects")
    suspend fun deleteProject(@Body project: ProjectDto)

    @GET("api/projects/by-name")
    suspend fun getProjectByName(@Query name: String?): ProjectDto
}
