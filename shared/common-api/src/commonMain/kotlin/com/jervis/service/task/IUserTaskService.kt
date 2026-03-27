package com.jervis.service.task

import com.jervis.dto.chat.AttachmentDto
import com.jervis.dto.chat.ChatMessageDto
import com.jervis.dto.user.TaskRoutingMode
import com.jervis.dto.user.UserTaskCountDto
import com.jervis.dto.user.UserTaskDto
import com.jervis.dto.user.UserTaskListPageDto
import com.jervis.dto.user.UserTaskPageDto
import kotlinx.rpc.annotations.Rpc

@Rpc
interface IUserTaskService {
    suspend fun listActive(clientId: String): List<UserTaskDto>

    suspend fun listAll(query: String? = null, offset: Int = 0, limit: Int = 20): UserTaskPageDto

    /** Lightweight paginated list — excludes content, attachments. Uses text index for search. */
    suspend fun listAllLightweight(query: String? = null, offset: Int = 0, limit: Int = 20): UserTaskListPageDto

    /** Get a single user task by ID with full details (content, attachments, chat context). */
    suspend fun getById(taskId: String): UserTaskDto?

    suspend fun activeCount(clientId: String): UserTaskCountDto

    suspend fun cancel(taskId: String): UserTaskDto

    suspend fun getChatHistory(taskId: String): List<ChatMessageDto>

    suspend fun sendToAgent(
        taskId: String,
        routingMode: TaskRoutingMode,
        additionalInput: String?,
        attachments: List<AttachmentDto> = emptyList(),
    ): UserTaskDto

    /** Respond to a task inline (from chat "Reagovat" button). Bypasses chat pipeline. */
    suspend fun respondToTask(taskId: String, response: String)

    /** Dismiss (ignore) a user task — moves to DONE without processing. Data preserved. */
    suspend fun dismiss(taskId: String)

    /** Dismiss ALL pending user tasks — bulk move to DONE. Returns count of dismissed tasks. */
    suspend fun dismissAll(): Int
}
