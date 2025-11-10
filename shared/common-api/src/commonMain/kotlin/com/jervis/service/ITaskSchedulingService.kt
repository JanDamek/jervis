package com.jervis.service

import com.jervis.domain.task.ScheduledTaskStatusEnum
import com.jervis.dto.ScheduledTaskDto
import de.jensklingenberg.ktorfit.http.DELETE
import de.jensklingenberg.ktorfit.http.GET
import de.jensklingenberg.ktorfit.http.POST
import de.jensklingenberg.ktorfit.http.PUT
import de.jensklingenberg.ktorfit.http.Path
import de.jensklingenberg.ktorfit.http.Query

interface ITaskSchedulingService {
    @POST("api/task-scheduling")
    suspend fun scheduleTask(
        @Query projectId: String,
        @Query taskName: String,
        @Query taskInstruction: String,
        @Query cronExpression: String?,
        @Query priority: Int,
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

    @GET("api/task-scheduling/pending")
    suspend fun listPendingTasks(): List<ScheduledTaskDto>

    @DELETE("api/task-scheduling/{taskId}")
    suspend fun cancelTask(
        @Path taskId: String,
    )

    @POST("api/task-scheduling/{taskId}/retry")
    suspend fun retryTask(
        @Path taskId: String,
    ): ScheduledTaskDto

    @PUT("api/task-scheduling/{taskId}/status")
    suspend fun updateTaskStatus(
        @Path taskId: String,
        @Query status: String,
        @Query errorMessage: String?,
    ): ScheduledTaskDto

    @GET("api/task-scheduling/by-status")
    fun getTasksByStatus(
        @Query taskStatus: ScheduledTaskStatusEnum,
    ): List<ScheduledTaskDto>
}
