package com.jervis.task

import com.jervis.dto.task.PagedPendingTasksResult
import com.jervis.dto.task.PendingTaskDto
import com.jervis.dto.task.SidebarSnapshot
import com.jervis.dto.task.TaskSnapshot
import com.jervis.service.task.IPendingTaskService

import com.jervis.infrastructure.error.ErrorLogService
import kotlinx.coroutines.flow.Flow
import mu.KotlinLogging
import org.springframework.stereotype.Component

@Component
class PendingTaskRpcImpl(
    private val pendingTaskService: com.jervis.task.PendingTaskService,
    private val sidebarStreamService: SidebarStreamService,
    private val taskStreamService: TaskStreamService,
) : IPendingTaskService {
    override fun subscribeSidebar(clientId: String?, showDone: Boolean): Flow<SidebarSnapshot> =
        sidebarStreamService.subscribe(clientId, showDone)

    override fun subscribeTask(taskId: String): Flow<TaskSnapshot> =
        taskStreamService.subscribe(taskId)

    override suspend fun listTasks(
        taskType: String?,
        state: String?,
        clientId: String?,
    ): List<PendingTaskDto> = pendingTaskService.listTasks(taskType, state, clientId)

    override suspend fun countTasks(
        taskType: String?,
        state: String?,
        clientId: String?,
    ): Long = pendingTaskService.countTasks(taskType, state, clientId)

    override suspend fun listTasksPaged(
        taskType: String?,
        state: String?,
        page: Int,
        pageSize: Int,
        clientId: String?,
        sourceScheme: String?,
        parentTaskId: String?,
        textQuery: String?,
    ): PagedPendingTasksResult = pendingTaskService.listTasksPaged(
        taskType, state, page, pageSize, clientId, sourceScheme, parentTaskId, textQuery,
    )

    override suspend fun getById(id: String): PendingTaskDto? = pendingTaskService.getById(id)

    override suspend fun listChildren(parentTaskId: String): List<PendingTaskDto> =
        pendingTaskService.listChildren(parentTaskId)

    override suspend fun deletePendingTask(id: String) = pendingTaskService.deletePendingTask(id)

    override suspend fun markDone(id: String, note: String?): PendingTaskDto? =
        pendingTaskService.markDone(id, note)

    override suspend fun reopen(id: String, note: String?): PendingTaskDto? =
        pendingTaskService.reopen(id, note)

    override suspend fun listRelatedTasks(taskId: String): List<PendingTaskDto> =
        pendingTaskService.listRelatedTasks(taskId)
}
