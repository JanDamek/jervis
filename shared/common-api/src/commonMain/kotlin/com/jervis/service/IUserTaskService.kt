package com.jervis.service

import com.jervis.dto.user.UserTaskCountDto
import com.jervis.dto.user.UserTaskDto
import de.jensklingenberg.ktorfit.http.GET
import de.jensklingenberg.ktorfit.http.PUT
import de.jensklingenberg.ktorfit.http.Query

interface IUserTaskService {
    @GET("api/user-tasks/active")
    suspend fun listActive(
        @Query clientId: String,
    ): List<UserTaskDto>

    @GET("api/user-tasks/active-count")
    suspend fun activeCount(
        @Query clientId: String,
    ): UserTaskCountDto

    @PUT("api/user-tasks/cancel")
    suspend fun cancel(
        @Query taskId: String,
    ): UserTaskDto
}
