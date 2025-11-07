package com.jervis.mobile.api

import com.jervis.dto.ChatRequestContextDto
import com.jervis.dto.ChatRequestDto
import com.jervis.dto.ChatResponseDto
import com.jervis.dto.ClientDto
import com.jervis.dto.ClientProjectLinkDto
import com.jervis.dto.ProjectDto
import com.jervis.dto.user.UserTaskCountDto
import com.jervis.mobile.ChatMessage
import com.jervis.mobile.MobileBootstrap
import com.jervis.mobile.MobileSelection
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.serialization.json.Json

/**
 * Ktor-based MobileAppFacade for Kotlin Multiplatform
 * Replaces Spring WebClient dependency with Ktor
 */
class KtorMobileAppFacade(
    private val bootstrap: MobileBootstrap,
) {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val httpClient =
        HttpClient {
            install(ContentNegotiation) {
                json(
                    Json {
                        ignoreUnknownKeys = true
                        prettyPrint = true
                        isLenient = true
                    },
                )
            }
            install(Logging) {
                level = LogLevel.INFO
            }
            install(WebSockets)
        }

    // State for UI binding
    private val _selection =
        MutableStateFlow(
            MobileSelection(
                clientId = bootstrap.clientId,
                projectId = bootstrap.defaultProjectId ?: "",
            ),
        )
    val selection: StateFlow<MobileSelection> = _selection.asStateFlow()

    private val _chat = MutableSharedFlow<ChatMessage>(extraBufferCapacity = 64)
    val chat: SharedFlow<ChatMessage> = _chat.asSharedFlow()

    private val _activeTasks = MutableStateFlow(UserTaskCountDto(clientId = bootstrap.clientId, activeCount = 0))
    val activeTasks: StateFlow<UserTaskCountDto> = _activeTasks.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    // WebSocket session ID (placeholder for now)
    val wsSessionId: String = "mobile-${Clock.System.now().toEpochMilliseconds()}"

    fun select(
        clientId: String,
        projectId: String,
    ) {
        _selection.value = MobileSelection(clientId, projectId)
    }

    suspend fun listClients(): Result<List<ClientDto>> =
        runCatching {
            httpClient.get("${bootstrap.serverBaseUrl}/api/clients").body()
        }

    suspend fun listProjectsForClient(clientId: String): Result<List<ProjectDto>> =
        runCatching {
            // Get linked projects
            val linked: List<ClientProjectLinkDto> =
                httpClient.get("${bootstrap.serverBaseUrl}/api/client-project-links/client/$clientId").body()
            val linkedProjectIds = linked.map { it.projectId }.toSet()

            // Get all projects
            val allProjects: List<ProjectDto> = httpClient.get("${bootstrap.serverBaseUrl}/api/projects").body()

            // Filter: if linked projects exist, use them; otherwise filter by clientId
            if (linkedProjectIds.isEmpty()) {
                allProjects.filter { it.clientId == clientId }
            } else {
                allProjects.filter { it.id in linkedProjectIds }
            }
        }

    suspend fun sendChat(
        text: String,
        quick: Boolean = false,
    ): Result<Unit> =
        runCatching {
            val sel = _selection.value
            require(sel.clientId.isNotBlank() && sel.projectId.isNotBlank()) { "Client and project must be selected" }

            _isLoading.value = true

            // Emit local copy of user message
            _chat.emit(ChatMessage(ChatMessage.Sender.Me, text))

            val ctx =
                ChatRequestContextDto(
                    clientId = sel.clientId,
                    projectId = sel.projectId,
                    autoScope = false,
                    quick = quick,
                    existingContextId = null,
                )

            val request = ChatRequestDto(text, ctx, wsSessionId = wsSessionId)

            // Send to server
            val response: ChatResponseDto =
                httpClient
                    .post("${bootstrap.serverBaseUrl}/api/agent/handle") {
                        contentType(ContentType.Application.Json)
                        setBody(request)
                    }.body()

            // Emit assistant response
            _chat.emit(
                ChatMessage(
                    from = ChatMessage.Sender.Assistant,
                    text = response.message,
                    contextId = null,
                    timestamp =
                        Clock.System
                            .now()
                            .toEpochMilliseconds()
                            .toString(),
                ),
            )

            _isLoading.value = false
        }.onFailure {
            _isLoading.value = false
        }

    suspend fun refreshActiveUserTasks(clientId: String): Result<UserTaskCountDto> =
        runCatching {
            val count: UserTaskCountDto =
                httpClient.get("${bootstrap.serverBaseUrl}/api/user-tasks/active-count/$clientId").body()
            _activeTasks.value = count
            count
        }

    fun startNotifications() {
        // TODO: Implement WebSocket connection for real-time notifications
        // For now, this is a placeholder
        scope.launch {
            // Simulate notifications
        }
    }

    fun stopNotifications() {
        // TODO: Close WebSocket connection
    }

    fun close() {
        httpClient.close()
    }
}
