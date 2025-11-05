package com.jervis.mobile

import com.jervis.dto.ChatRequestContextDto
import com.jervis.dto.ChatRequestDto
import com.jervis.dto.ProjectDto
import com.jervis.dto.user.UserTaskCountDto
import com.jervis.service.IAgentOrchestratorService
import com.jervis.service.IClientProjectLinkService
import com.jervis.service.IClientService
import com.jervis.service.IProjectService
import com.jervis.service.IUserTaskService
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
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.support.WebClientAdapter
import org.springframework.web.service.invoker.HttpServiceProxyFactory

private val logger = KotlinLogging.logger {}

/**
 * Entry facade for Mobile UI. Platform UI (Android/iOS) consumes this API.
 * No UI is implemented here to respect architecture (UI belongs to platform apps).
 */
class MobileAppFacade(
    private val bootstrap: MobileBootstrap,
) {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val webClient: WebClient by lazy {
        WebClient.builder()
            .baseUrl(bootstrap.serverBaseUrl)
            .build()
    }

    private val httpFactory: HttpServiceProxyFactory by lazy {
        HttpServiceProxyFactory
            .builderFor(WebClientAdapter.create(webClient))
            .build()
    }

    // Services from :common
    private val agentService: IAgentOrchestratorService by lazy { httpFactory.createClient(IAgentOrchestratorService::class.java) }
    private val clientService: IClientService by lazy { httpFactory.createClient(IClientService::class.java) }
    private val projectService: IProjectService by lazy { httpFactory.createClient(IProjectService::class.java) }
    private val linkService: IClientProjectLinkService by lazy { httpFactory.createClient(IClientProjectLinkService::class.java) }
    private val userTaskService: IUserTaskService by lazy { httpFactory.createClient(IUserTaskService::class.java) }

    // Notifications (online only)
    private val notifications = MobileNotifications(bootstrap.serverBaseUrl)

    // State for UI binding
    private val _selection = MutableStateFlow(
        MobileSelection(
            clientId = bootstrap.clientId,
            projectId = bootstrap.defaultProjectId ?: ""
        )
    )
    val selection: StateFlow<MobileSelection> = _selection.asStateFlow()

    private val _chat = MutableSharedFlow<ChatMessage>(extraBufferCapacity = 64)
    val chat: SharedFlow<ChatMessage> = _chat.asSharedFlow()

    private val _activeTasks = MutableStateFlow(UserTaskCountDto(clientId = bootstrap.clientId, activeCount = 0))
    val activeTasks: StateFlow<UserTaskCountDto> = _activeTasks.asStateFlow()

    val wsSessionId: String get() = notifications.sessionId

    fun startNotifications() {
        notifications.start()
        scope.launch { notifications.agentResponses.collect { onAgentResponse(it) } }
        scope.launch { notifications.errors.collect { logger.warn { "Server error: ${it.message}" } } }
    }

    fun stopNotifications() {
        notifications.stop()
    }

    private fun onAgentResponse(evt: com.jervis.dto.events.AgentResponseEventDto) {
        val from = ChatMessage.Sender.Assistant
        scope.launch { _chat.emit(ChatMessage(from, evt.message, contextId = evt.contextId, timestamp = evt.timestamp)) }
    }

    suspend fun listClients() = withContext(Dispatchers.IO) { clientService.list() }

    suspend fun listProjectsForClient(clientId: String): List<ProjectDto> = withContext(Dispatchers.IO) {
        val linked = linkService.listForClient(clientId).map { it.projectId }.toSet()
        val all = projectService.getAllProjects()
        if (linked.isEmpty()) all.filter { it.clientId == clientId } else all.filter { it.id in linked }
    }

    fun select(clientId: String, projectId: String) {
        _selection.value = MobileSelection(clientId, projectId)
    }

    suspend fun sendChat(text: String, quick: Boolean = false): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val sel = _selection.value
            require(sel.clientId.isNotBlank() && sel.projectId.isNotBlank()) { "Client and project must be selected" }
            // emit local copy of my message
            _chat.emit(ChatMessage(ChatMessage.Sender.Me, text))
            val ctx = ChatRequestContextDto(
                clientId = sel.clientId,
                projectId = sel.projectId,
                autoScope = false,
                quick = quick,
                existingContextId = null,
            )
            agentService.handle(ChatRequestDto(text, ctx, wsSessionId = wsSessionId))
        }
    }

    suspend fun refreshActiveUserTasks(clientId: String): Result<UserTaskCountDto> = withContext(Dispatchers.IO) {
        runCatching {
            userTaskService.activeCount(clientId).also { _activeTasks.value = it }
        }
    }
}
