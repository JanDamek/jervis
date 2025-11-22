package com.jervis.service

import com.jervis.dto.ScheduledTaskDto
import de.jensklingenberg.ktorfit.http.DELETE
import de.jensklingenberg.ktorfit.http.GET
import de.jensklingenberg.ktorfit.http.POST
import de.jensklingenberg.ktorfit.http.Path
import de.jensklingenberg.ktorfit.http.Query

interface ITaskSchedulingService {
    @POST("api/task-scheduling")
    suspend fun scheduleTask(
        @Query clientId: String,
        @Query projectId: String?,
        @Query taskName: String,
        @Query content: String,
        @Query cronExpression: String?,
        @Query correlationId: String?,
    ): ScheduledTaskDto

    @GET("api/task-scheduling/{taskId}")
    suspend fun findById(
        @Path taskId: String,
    ): ScheduledTaskDto?

    @GET("api/task-scheduling/list")
    suspend fun listAllTasks(): List<ScheduledTaskDto>

    @GET("api/task-scheduling/project/{projectId}")
    suspend fun listTasksForProject(
        @Path projectId: String,
    ): List<ScheduledTaskDto>

    @GET("api/task-scheduling/client/{clientId}")
    suspend fun listTasksForClient(
        @Path clientId: String,
    ): List<ScheduledTaskDto>

    @DELETE("api/task-scheduling/{taskId}")
    suspend fun cancelTask(
        @Path taskId: String,
    )
}
