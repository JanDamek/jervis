package com.jervis.service.task

import com.jervis.dto.task.PagedPendingTasksResult
import com.jervis.dto.task.PendingTaskDto
import com.jervis.dto.task.SidebarSnapshot
import com.jervis.dto.task.TaskSnapshot
import kotlinx.coroutines.flow.Flow
import kotlinx.rpc.annotations.Rpc

@Rpc
interface IPendingTaskService {
    /**
     * Push-only sidebar stream. Emits a new snapshot on every task write
     * in the given scope. Guideline #9 — UI does not pull; this is the
     * canonical source of the active-tasks sidebar.
     *
     * @param clientId filter by client (null = global scope)
     * @param showDone true = DONE history; false = active states
     */
    fun subscribeSidebar(clientId: String?, showDone: Boolean): Flow<SidebarSnapshot>

    /**
     * Push-only per-task stream for the drill-in breadcrumb + brief.
     * Emits on task state changes, content updates, and related-task updates.
     */
    fun subscribeTask(taskId: String): Flow<TaskSnapshot>

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

    /**
     * Phase 5 — user (or JERVIS itself) marks a task as DONE.
     * Same action available to both: in the chat task brief there is a
     * 'Označit jako hotové' button, and the chat agent has a tool that
     * calls this same endpoint. The task transitions to state=DONE,
     * lastActivityAt is updated. The task is preserved in the DB.
     */
    suspend fun markDone(id: String, note: String? = null): PendingTaskDto?

    /**
     * Phase 5 — user (or JERVIS itself) reopens a previously DONE task.
     * The task transitions to state=NEW with needsQualification=true so
     * the re-entrant qualifier picks it up and decides what to do next.
     * Used when the user remembers something or wants to revisit.
     */
    suspend fun reopen(id: String, note: String? = null): PendingTaskDto?

    /**
     * Returns tasks that share KB graph entities with the given task.
     * Used in the chat brief to surface related context.
     * Returns at most 10 tasks, excluding the task itself.
     */
    suspend fun listRelatedTasks(taskId: String): List<PendingTaskDto>
}
