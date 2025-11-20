package com.jervis.service

import com.jervis.dto.PendingTaskDto
import de.jensklingenberg.ktorfit.http.DELETE
import de.jensklingenberg.ktorfit.http.GET
import de.jensklingenberg.ktorfit.http.Path
import de.jensklingenberg.ktorfit.http.Query

/**
 * Pending Task API for UI
 */
interface IPendingTaskService {
    @GET("api/pending-tasks")
    suspend fun listPendingTasks(
        @Query("taskType") taskType: String? = null,
        @Query("state") state: String? = null,
    ): List<PendingTaskDto>

    @GET("api/pending-tasks/count")
    suspend fun countPendingTasks(
        @Query("taskType") taskType: String? = null,
        @Query("state") state: String? = null,
    ): Long

    @DELETE("api/pending-tasks/{id}")
    suspend fun deletePendingTask(
        @Path("id") id: String,
    )
}
