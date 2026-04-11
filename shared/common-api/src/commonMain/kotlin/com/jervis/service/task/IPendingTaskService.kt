package com.jervis.service.task

import com.jervis.dto.task.PagedPendingTasksResult
import com.jervis.dto.task.PendingTaskDto
import kotlinx.rpc.annotations.Rpc

@Rpc
interface IPendingTaskService {
    suspend fun listTasks(
        taskType: String? = null,
        state: String? = null,
        clientId: String? = null,
    ): List<PendingTaskDto>

    suspend fun countTasks(
        taskType: String? = null,
        state: String? = null,
        clientId: String? = null,
    ): Long

    /**
     * Paginated list + count in a single RPC call.
     * Replaces parallel listTasks + countTasks.
     *
     * Phase 4: [sourceScheme] filters by SourceUrn prefix (e.g. "email",
     * "whatsapp"). [parentTaskId] drills into a parent's sub-tasks.
     * [textQuery] does a substring match on `taskName` + `content`.
     */
    suspend fun listTasksPaged(
        taskType: String? = null,
        state: String? = null,
        page: Int = 0,
        pageSize: Int = 50,
        clientId: String? = null,
        sourceScheme: String? = null,
        parentTaskId: String? = null,
        textQuery: String? = null,
    ): PagedPendingTasksResult

    /** Phase 4: load a single task by id for the detail panel. */
    suspend fun getById(id: String): PendingTaskDto?

    /** Phase 4: list direct children of a parent task. Used for sub-task hierarchy view. */
    suspend fun listChildren(parentTaskId: String): List<PendingTaskDto>

    suspend fun deletePendingTask(id: String)
}
