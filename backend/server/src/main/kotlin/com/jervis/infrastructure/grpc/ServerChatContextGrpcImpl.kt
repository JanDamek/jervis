package com.jervis.infrastructure.grpc

import com.jervis.chat.ChatMessageService
import com.jervis.chat.ChatService
import com.jervis.chat.MessageRole
import com.jervis.client.ClientService
import com.jervis.contracts.server.ActiveChatTopicsRequest
import com.jervis.contracts.server.ActiveChatTopicsResponse
import com.jervis.contracts.server.ChatTopicMessage
import com.jervis.contracts.server.ClientWithProjects
import com.jervis.contracts.server.ClientsProjectsRequest
import com.jervis.contracts.server.ClientsProjectsResponse
import com.jervis.contracts.server.PendingUserTask
import com.jervis.contracts.server.PendingUserTasksRequest
import com.jervis.contracts.server.PendingUserTasksResponse
import com.jervis.contracts.server.ProjectLite
import com.jervis.contracts.server.ServerChatContextServiceGrpcKt
import com.jervis.contracts.server.UnclassifiedCountRequest
import com.jervis.contracts.server.UnclassifiedCountResponse
import com.jervis.contracts.server.UserTimezoneRequest
import com.jervis.contracts.server.UserTimezoneResponse
import com.jervis.dto.task.TaskStateEnum
import com.jervis.meeting.MeetingRpcImpl
import com.jervis.preferences.PreferenceService
import com.jervis.project.ProjectService
import com.jervis.task.UserTaskService
import mu.KotlinLogging
import org.springframework.stereotype.Component

// Runtime context that the Python chat handler injects into the LLM
// system prompt. Every response must handle partial failures gracefully
// — an empty list or zero count is the documented fallback so the LLM
// still has a valid system prompt.
@Component
class ServerChatContextGrpcImpl(
    private val clientService: ClientService,
    private val projectService: ProjectService,
    private val userTaskService: UserTaskService,
    private val meetingRpcImpl: MeetingRpcImpl,
    private val preferenceService: PreferenceService,
    private val chatService: ChatService,
    private val chatMessageService: ChatMessageService,
) : ServerChatContextServiceGrpcKt.ServerChatContextServiceCoroutineImplBase() {
    private val logger = KotlinLogging.logger {}

    override suspend fun listClientsProjects(request: ClientsProjectsRequest): ClientsProjectsResponse {
        val entries = try {
            clientService.list().filter { !it.archived }.map { client ->
                val projects = projectService.listProjectsForClient(client.id).map { p ->
                    ProjectLite.newBuilder()
                        .setId(p.id.toString())
                        .setName(p.name)
                        .build()
                }
                ClientWithProjects.newBuilder()
                    .setId(client.id.toString())
                    .setName(client.name)
                    .addAllProjects(projects)
                    .build()
            }
        } catch (e: Exception) {
            logger.warn(e) { "CHAT_CONTEXT_LIST_CLIENTS failed" }
            emptyList()
        }
        return ClientsProjectsResponse.newBuilder().addAllClients(entries).build()
    }

    override suspend fun pendingUserTasksSummary(request: PendingUserTasksRequest): PendingUserTasksResponse {
        val limit = if (request.limit > 0) request.limit else 3
        return try {
            val result = userTaskService.findPagedTasks(
                query = null,
                offset = 0,
                limit = limit,
                stateFilter = TaskStateEnum.USER_TASK,
            )
            val tasks = result.items.map { task ->
                PendingUserTask.newBuilder()
                    .setId(task.id.toString())
                    .setTitle(task.taskName)
                    .setQuestion(task.pendingUserQuestion ?: "")
                    .setClientId(task.clientId.toString())
                    .setProjectId(task.projectId?.toString() ?: "")
                    .build()
            }
            PendingUserTasksResponse.newBuilder()
                .setCount(result.totalCount)
                .addAllTasks(tasks)
                .build()
        } catch (e: Exception) {
            logger.warn(e) { "CHAT_CONTEXT_PENDING_TASKS failed" }
            PendingUserTasksResponse.newBuilder().setCount(0).build()
        }
    }

    override suspend fun unclassifiedMeetingsCount(request: UnclassifiedCountRequest): UnclassifiedCountResponse {
        return try {
            UnclassifiedCountResponse.newBuilder()
                .setCount(meetingRpcImpl.listUnclassifiedMeetings().size)
                .build()
        } catch (e: Exception) {
            logger.warn(e) { "CHAT_CONTEXT_UNCLASSIFIED failed" }
            UnclassifiedCountResponse.newBuilder().setCount(0).build()
        }
    }

    override suspend fun getUserTimezone(request: UserTimezoneRequest): UserTimezoneResponse {
        return try {
            UserTimezoneResponse.newBuilder()
                .setTimezone(preferenceService.getUserTimezone().id)
                .build()
        } catch (e: Exception) {
            logger.warn(e) { "CHAT_CONTEXT_TIMEZONE failed" }
            UserTimezoneResponse.newBuilder().setTimezone("Europe/Prague").build()
        }
    }

    override suspend fun getActiveChatTopics(
        request: ActiveChatTopicsRequest,
    ): ActiveChatTopicsResponse {
        return try {
            val limit = if (request.maxMessages > 0) request.maxMessages else 10
            val session = chatService.getOrCreateActiveSession()
            val messages = chatMessageService.getLastMessages(session.id, limit)
            val builder = ActiveChatTopicsResponse.newBuilder()
                .setClientId(session.lastClientId ?: "")
                .setProjectId(session.lastProjectId ?: "")
            messages
                .filter { it.role == MessageRole.USER || it.role == MessageRole.ASSISTANT }
                .forEach { msg ->
                    builder.addTopics(
                        ChatTopicMessage.newBuilder()
                            .setRole(msg.role.name.lowercase())
                            .setContent(msg.content)
                            .build(),
                    )
                }
            builder.build()
        } catch (e: Exception) {
            logger.warn(e) { "CHAT_CONTEXT_ACTIVE_TOPICS failed" }
            ActiveChatTopicsResponse.newBuilder().build()
        }
    }
}
