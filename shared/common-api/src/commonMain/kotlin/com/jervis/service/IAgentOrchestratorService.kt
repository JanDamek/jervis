package com.jervis.service

import com.jervis.dto.ChatResponseDto
import com.jervis.dto.PendingTasksDto
import com.jervis.dto.PendingTasksPageDto
import com.jervis.dto.TaskHistoryPageDto
import kotlinx.coroutines.flow.Flow
import kotlinx.rpc.annotations.Rpc

@Rpc
interface IAgentOrchestratorService {
    /**
     * Subscribe to queue status updates for given client.
     * Returns Flow of queue status messages with running project and queue size.
     */
    fun subscribeToQueueStatus(clientId: String): Flow<ChatResponseDto>

    // --- Queue Management ---

    /**
     * Get all pending tasks for both FOREGROUND and BACKGROUND queues.
     * Returns structured data with queue items and running task info.
     */
    suspend fun getPendingTasks(clientId: String): PendingTasksDto

    /**
     * Get paginated background tasks for infinite scroll.
     */
    suspend fun getBackgroundTasksPage(limit: Int, offset: Int): PendingTasksPageDto

    /**
     * Reorder a task within its queue by setting a new position.
     * Only works for tasks in READY_FOR_GPU state.
     */
    suspend fun reorderTask(taskId: String, newPosition: Int)

    /**
     * Move a task between FOREGROUND and BACKGROUND queues.
     * Only works for tasks in READY_FOR_GPU state.
     * @param targetMode "FOREGROUND" or "BACKGROUND"
     */
    suspend fun moveTask(taskId: String, targetMode: String)

    /**
     * Cancel a running orchestration for a given task.
     */
    suspend fun cancelOrchestration(taskId: String)

    // --- Task History ---

    /**
     * Get paginated task history (completed orchestrator tasks).
     * Ordered by completedAt DESC (newest first).
     */
    suspend fun getTaskHistory(limit: Int = 20, offset: Int = 0): TaskHistoryPageDto
}
