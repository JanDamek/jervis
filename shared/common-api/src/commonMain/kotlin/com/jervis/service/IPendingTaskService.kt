package com.jervis.service

import com.jervis.dto.PendingTaskDto
import de.jensklingenberg.ktorfit.http.DELETE
import de.jensklingenberg.ktorfit.http.GET
import de.jensklingenberg.ktorfit.http.Path

/**
 * Pending Task API for UI
 */
interface IPendingTaskService {
    @GET("api/pending-tasks")
    suspend fun listPendingTasks(): List<PendingTaskDto>

    @DELETE("api/pending-tasks/{id}")
    suspend fun deletePendingTask(
        @Path("id") id: String,
    )
}
