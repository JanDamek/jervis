package com.jervis.ui

import com.jervis.di.RpcConnectionManager
import com.jervis.di.RpcConnectionState
import com.jervis.dto.ClientDto
import com.jervis.dto.ProjectDto
import com.jervis.dto.filterVisible
import com.jervis.dto.events.JervisEvent
import com.jervis.dto.ui.ChatMessage
import com.jervis.repository.JervisRepository
import com.jervis.ui.chat.ChatViewModel
import com.jervis.ui.notification.NotificationViewModel
import com.jervis.ui.notification.PushTokenRegistrar
import com.jervis.ui.storage.CachedData
import com.jervis.ui.storage.OfflineDataCache
import com.jervis.ui.environment.EnvironmentViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * ViewModel for MainScreen — coordinates sub-ViewModels and manages client/project selection.
 */
class MainViewModel(
    private val repository: JervisRepository,
    private val connectionManager: RpcConnectionManager,
    defaultClientId: String? = null,
    defaultProjectId: String? = null,
) {
    // Global exception handler to prevent app crashes
    private val exceptionHandler =
        CoroutineExceptionHandler { _, exception ->
            println("Uncaught exception in MainViewModel: ${exception.message}")
            exception.printStackTrace()

            if (exception is CancellationException) {
                // Ignore cancellations — RpcConnectionManager handles reconnection
            } else {
                _errorMessage.value = "An unexpected error occurred: ${exception.message}"
            }
        }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob() + exceptionHandler)

    // Client/project selection state
    private val _clients = MutableStateFlow<List<ClientDto>>(emptyList())
    val clients: StateFlow<List<ClientDto>> = _clients.asStateFlow()

    // Signals that clients have been loaded from server at least once
    private val clientsLoaded = MutableStateFlow(false)

    private val _projects = MutableStateFlow<List<ProjectDto>>(emptyList())
    val projects: StateFlow<List<ProjectDto>> = _projects.asStateFlow()

    private val _projectGroups = MutableStateFlow<List<com.jervis.dto.ProjectGroupDto>>(emptyList())
    val projectGroups: StateFlow<List<com.jervis.dto.ProjectGroupDto>> = _projectGroups.asStateFlow()

    private val _selectedClientId = MutableStateFlow<String?>(defaultClientId)
    val selectedClientId: StateFlow<String?> = _selectedClientId.asStateFlow()

    private val _selectedProjectId = MutableStateFlow<String?>(defaultProjectId)
    val selectedProjectId: StateFlow<String?> = _selectedProjectId.asStateFlow()

    private val _selectedGroupId = MutableStateFlow<String?>(null)
    val selectedGroupId: StateFlow<String?> = _selectedGroupId.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    // --- Sub-ViewModels ---

    val connection = ConnectionViewModel(connectionManager)

    val notification = NotificationViewModel(repository, _selectedClientId)

    val queue = com.jervis.ui.queue.QueueViewModel(repository, connectionManager, _selectedClientId)

    val environment = EnvironmentViewModel(repository, _selectedClientId, _selectedProjectId)

    val chat = ChatViewModel(
        repository = repository,
        connectionManager = connectionManager,
        selectedClientId = _selectedClientId,
        selectedProjectId = _selectedProjectId,
        onScopeChange = ::updateChatScope,
        onConnectionReady = { connection.updateState(ConnectionViewModel.State.CONNECTED) },
        onError = ::reportError,
    )

    // Workspace status for selected project (derived from _projects + _selectedProjectId)
    data class WorkspaceInfo(
        val status: String?,    // READY, CLONING, CLONE_FAILED_AUTH/NETWORK/NOT_FOUND/OTHER, NOT_NEEDED, null
        val error: String?,
        val retryCount: Int,
        val nextRetryAt: String?,
    )

    val workspaceInfo: StateFlow<WorkspaceInfo?> = combine(_projects, _selectedProjectId) { projects, projectId ->
        if (projectId == null) return@combine null
        val project = projects.find { it.id == projectId } ?: return@combine null
        val ws = project.workspaceStatus
        if (ws == null || ws == "NOT_NEEDED" || ws == "READY") return@combine null
        WorkspaceInfo(
            status = project.workspaceStatus,
            error = project.workspaceError,
            retryCount = project.workspaceRetryCount,
            nextRetryAt = project.nextWorkspaceRetryAt,
        )
    }.stateIn(scope, SharingStarted.WhileSubscribed(5000), null)

    private var eventJob: Job? = null
    private var globalEventJob: Job? = null

    init {
        // Wire queue → chat progress updates
        queue.onChatProgressUpdate = { text, isError ->
            chat.replaceChatProgress(text, if (isError) ChatMessage.MessageType.ERROR else ChatMessage.MessageType.PROGRESS)
        }

        // Load offline cache at startup — show cached data while connecting
        val cached = OfflineDataCache.load()
        if (cached != null && _clients.value.isEmpty()) {
            _clients.value = cached.clients
            connection.setInitialLoading(false)
        }

        // Observe RpcConnectionManager state → drive local connection state
        scope.launch {
            connectionManager.state.collect { rpcState ->
                val gen = connectionManager.generation.value
                println("MainViewModel: RpcConnectionState changed to ${rpcState::class.simpleName} (gen=$gen)")
                when (rpcState) {
                    is RpcConnectionState.Connected -> {
                        connection.updateState(ConnectionViewModel.State.CONNECTED)

                        // Always load clients when connected (refresh from server)
                        loadClients()

                        // Subscribe to global queue status on every (re)connect
                        queue.subscribeToQueueStatus("_global")

                        // Subscribe to global events (qualification progress, orchestrator)
                        subscribeToGlobalEventStream()
                    }
                    is RpcConnectionState.Connecting -> {
                        connection.updateState(ConnectionViewModel.State.RECONNECTING)
                    }
                    is RpcConnectionState.Disconnected -> {
                        connection.updateState(ConnectionViewModel.State.DISCONNECTED)
                    }
                }
            }
        }

        // Auto-load projects if client is pre-selected
        _selectedClientId.value?.let { clientId ->
            selectClient(clientId)
        }

        // Subscribe to global chat stream (no longer per client/project)
        scope.launch {
            chat.subscribeToChatStream()
        }

        // Subscribe to global events for the selected client + register FCM token
        scope.launch {
            _selectedClientId.collect { clientId ->
                println("MainViewModel: _selectedClientId changed to $clientId")
                if (clientId != null) {
                    subscribeToEventStream(clientId)
                    // Register push token (FCM on Android, APNs on iOS, desktop)
                    scope.launch {
                        PushTokenRegistrar.registerIfNeeded(clientId, repository.deviceTokens)
                    }
                } else {
                    eventJob?.cancel()
                }
            }
        }
    }

    // --- Event Streams ---

    /**
     * Global event stream — subscribes with "__global__" clientId.
     * Receives broadcasts (QualificationProgress, OrchestratorTaskProgress, etc.)
     * independent of selected client. Always active when connected.
     */
    private fun subscribeToGlobalEventStream() {
        globalEventJob?.cancel()
        globalEventJob = scope.launch {
            connectionManager.resilientFlow { services ->
                services.notificationService.subscribeToEvents("__global__")
            }.collect { event ->
                handleGlobalEvent(event)
            }
        }
    }

    private fun subscribeToEventStream(clientId: String) {
        println("MainViewModel: subscribeToEventStream(client=$clientId) — cancelling previous eventJob")
        eventJob?.cancel()
        eventJob = scope.launch {
            connectionManager.resilientFlow { services ->
                println("MainViewModel: resilientFlow subscribing to events (client=$clientId, gen=${connectionManager.generation.value})")
                services.notificationService.subscribeToEvents(clientId)
            }.collect { event ->
                handleGlobalEvent(event)
            }
        }
    }

    private fun handleGlobalEvent(event: JervisEvent) {
        println("Received global event: ${event::class.simpleName}")
        when (event) {
            is JervisEvent.UserTaskCreated -> notification.handleUserTaskCreated(event)
            is JervisEvent.UserTaskCancelled -> notification.handleUserTaskCancelled(event)
            is JervisEvent.ErrorNotification -> _errorMessage.value = "Server error: ${event.message}"
            is JervisEvent.MeetingStateChanged -> { /* Handled by MeetingViewModel */ }
            is JervisEvent.MeetingTranscriptionProgress -> { /* Handled by MeetingViewModel */ }
            is JervisEvent.MeetingCorrectionProgress -> { /* Handled by MeetingViewModel */ }
            is JervisEvent.OrchestratorTaskProgress -> queue.handleOrchestratorProgress(event)
            is JervisEvent.OrchestratorTaskStatusChange -> queue.handleOrchestratorStatusChange(event)
            is JervisEvent.QualificationProgress -> queue.handleQualificationProgress(event)
            is JervisEvent.PendingTaskCreated -> { /* Handled elsewhere if needed */ }
        }
    }

    // --- Client/Project Selection ---

    fun loadClients() {
        scope.launch {
            val isFirstLoad = _clients.value.isEmpty()
            _isLoading.value = isFirstLoad
            connection.setInitialLoading(isFirstLoad)
            try {
                val clientList = repository.clients.getAllClients()
                _clients.value = clientList
                clientsLoaded.value = true
                // Persist to offline cache
                saveClientsToCache(clientList)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                // If offline and we have cached data, don't show error
                if (_clients.value.isEmpty()) {
                    _errorMessage.value = "Failed to load clients: ${e.message}"
                }
            } finally {
                _isLoading.value = false
                connection.setInitialLoading(false)
            }
        }
    }

    private fun saveClientsToCache(clients: List<ClientDto>) {
        try {
            val existing = OfflineDataCache.load() ?: CachedData()
            OfflineDataCache.save(existing.copy(clients = clients))
        } catch (_: Exception) {
            // Non-critical — cache save failure is fine
        }
    }

    private fun saveProjectsToCache(clientId: String, projects: List<ProjectDto>) {
        try {
            val existing = OfflineDataCache.load() ?: CachedData()
            OfflineDataCache.save(existing.copy(
                projectsByClient = existing.projectsByClient + (clientId to projects)
            ))
        } catch (_: Exception) {
            // Non-critical
        }
    }

    /**
     * Scope update from chat — called on scope_change events and history restore.
     *
     * Two modes:
     * 1. Live scope_change (projectsJson present) — lightweight update with embedded project list
     * 2. History restore (no projectsJson) — delegates to selectClient for full flow
     *
     * Waits for clients to be loaded before applying scope (prevents race condition on startup).
     */
    private fun updateChatScope(clientId: String, projectId: String? = null, projectsJson: String? = null) {
        if (clientId == _selectedClientId.value && projectId == _selectedProjectId.value) return
        println("MainViewModel: updateChatScope(client=$clientId, project=$projectId)")

        scope.launch {
            // Wait for clients to be loaded (prevents race condition on app startup)
            clientsLoaded.first { it }

            // Re-check after waiting — another scope change may have already applied
            if (clientId == _selectedClientId.value && projectId == _selectedProjectId.value) return@launch

            val clientChanged = clientId != _selectedClientId.value

            if (!projectsJson.isNullOrBlank()) {
                // Live scope_change with embedded projects — lightweight update
                _selectedClientId.value = clientId
                try {
                    val jsonArray = Json.parseToJsonElement(projectsJson).jsonArray
                    val parsed = jsonArray.map { element ->
                        val obj = element.jsonObject
                        ProjectDto(
                            id = obj["id"]?.jsonPrimitive?.content ?: "",
                            clientId = clientId,
                            name = obj["name"]?.jsonPrimitive?.content ?: "",
                        )
                    }
                    _projects.value = parsed
                } catch (e: Exception) {
                    println("Failed to parse scope_change projects: ${e.message}")
                }
                _selectedProjectId.value = projectId
                persistChatScope(clientId, projectId)
            } else if (clientChanged) {
                // History restore or scope_change without projects — full selectClient flow
                selectClient(clientId, chatProjectId = projectId)
            } else if (projectId != _selectedProjectId.value) {
                // Same client, different project
                _selectedProjectId.value = projectId
                persistChatScope(clientId, projectId)
            }
        }
    }

    /**
     * Select a client in the UI. Loads projects, groups, environments, badges.
     *
     * @param chatProjectId If set (from chat history restore), overrides lastSelectedProjectId.
     *                      Does NOT persist scope (already saved in chat session).
     */
    fun selectClient(clientId: String, chatProjectId: String? = null) {
        if (_selectedClientId.value == clientId && chatProjectId == null) return
        println("MainViewModel: selectClient($clientId, chatProject=$chatProjectId) — previous: ${_selectedClientId.value}")

        // Chat is global — do NOT cancel chatJob or clear chatMessages here.

        _selectedProjectId.value = null
        _selectedGroupId.value = null
        _selectedClientId.value = clientId

        // Only persist scope on manual user switch, not on chat restore
        if (chatProjectId == null) {
            persistChatScope(clientId, null)
        }

        _projects.value = emptyList()
        _projectGroups.value = emptyList()

        // Reset environment state for new client
        environment.resetForClient()

        // Global mode — no projects, no environments, just chat
        if (clientId == "__global__") {
            return
        }

        // Load cached projects immediately (shown while server loads)
        val cached = OfflineDataCache.load()
        cached?.projectsByClient?.get(clientId)?.let { cachedProjects ->
            if (_projects.value.isEmpty()) {
                _projects.value = cachedProjects
                val projectToSelect = chatProjectId
                    ?: _clients.value.find { it.id == clientId }?.lastSelectedProjectId
                if (projectToSelect != null && cachedProjects.any { it.id == projectToSelect }) {
                    _selectedProjectId.value = projectToSelect
                }
            }
        }

        scope.launch {
            _isLoading.value = true
            try {
                // Load projects and groups for this client
                val projectList = repository.projects.listProjectsForClient(clientId).filterVisible()
                _projects.value = projectList
                saveProjectsToCache(clientId, projectList)

                val allGroups = repository.projectGroups.getAllGroups()
                val clientGroups = allGroups.filter { it.clientId == clientId }
                _projectGroups.value = clientGroups

                // chatProjectId (from chat session) has priority over lastSelectedProjectId
                val projectToSelect = chatProjectId
                    ?: _clients.value.find { it.id == clientId }?.lastSelectedProjectId
                if (projectToSelect != null && projectList.any { it.id == projectToSelect }) {
                    _selectedProjectId.value = projectToSelect
                }

                // Eagerly load environments to determine badge visibility
                environment.loadEnvironments(clientId)

                // Load active user task count for badge
                notification.refreshUserTaskCount()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                // If offline and we have cached projects, don't show error
                if (_projects.value.isEmpty()) {
                    _errorMessage.value = "Failed to load projects: ${e.message}"
                }
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun selectGroup(groupId: String) {
        if (_selectedGroupId.value == groupId) return

        _selectedGroupId.value = groupId
        _selectedProjectId.value = null // Clear project selection when group is selected
        // Chat is global — do NOT clear chatMessages or cancel chatJob here.
    }

    fun selectProject(projectId: String) {
        if (projectId.isBlank()) {
            _selectedProjectId.value = null
            return
        }
        if (_selectedProjectId.value == projectId) return
        println("MainViewModel: selectProject($projectId) — previous: ${_selectedProjectId.value}")

        _selectedProjectId.value = projectId
        _selectedGroupId.value = null
        // Chat is global — do NOT cancel chatJob or clear chatMessages here.

        // Persist scope to chat session (so restart restores correct project)
        val clientId = _selectedClientId.value
        persistChatScope(clientId, projectId)

        // Save selection to server
        if (clientId != null) {
            scope.launch {
                try {
                    val updatedClient = repository.clients.updateLastSelectedProject(clientId, projectId)
                    _clients.value =
                        _clients.value.map {
                            if (it.id == clientId) updatedClient else it
                        }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    // Silent fail - not critical
                }
            }
        }
    }

    fun retryWorkspace() {
        val projectId = _selectedProjectId.value ?: return
        scope.launch {
            try {
                repository.projects.retryWorkspace(projectId)
                // Refresh projects to pick up workspace status change
                _selectedClientId.value?.let { selectClient(it) }
            } catch (_: Exception) {
                // Ignore — will retry automatically via backoff
            }
        }
    }

    /**
     * Persist current scope to chat session (fire-and-forget).
     * Called on manual client/project switch so that app restart restores correct scope.
     */
    private fun persistChatScope(clientId: String?, projectId: String?) {
        scope.launch {
            try {
                repository.chat.updateScope(clientId = clientId, projectId = projectId)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                // Non-critical — scope will be updated on next message send
            }
        }
    }

    fun reportError(message: String) {
        _errorMessage.value = message
    }

    fun clearError() {
        _errorMessage.value = null
    }

    fun onDispose() {
        environment.onDispose()
    }
}
