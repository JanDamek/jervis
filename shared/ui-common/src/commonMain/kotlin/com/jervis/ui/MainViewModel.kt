package com.jervis.ui

import com.jervis.dto.ChatRequestContextDto
import com.jervis.dto.ChatRequestDto
import com.jervis.dto.ChatResponseType
import com.jervis.dto.ClientDto
import com.jervis.dto.ProjectDto
import com.jervis.dto.ui.ChatMessage
import com.jervis.repository.JervisRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.seconds

/**
 * ViewModel for MainScreen
 * Manages state and business logic for the UI
 */
class MainViewModel(
    private val repository: JervisRepository,
    defaultClientId: String? = null,
    defaultProjectId: String? = null,
    private val onRefreshConnection: (() -> Unit)? = null,
) {
    // Global exception handler to prevent app crashes
    private val exceptionHandler =
        CoroutineExceptionHandler { _, exception ->
            println("Uncaught exception in MainViewModel: ${exception.message}")
            exception.printStackTrace()

            // Check if it's a cancellation or "Cancelled" error
            val isCancelled =
                exception is CancellationException ||
                    exception.message?.contains("cancelled", ignoreCase = true) == true ||
                    exception.message?.contains("RpcClient was cancelled", ignoreCase = true) == true ||
                    exception.message?.contains("Client cancelled", ignoreCase = true) == true

            if (isCancelled) {
                _connectionState.value = ConnectionState.DISCONNECTED
                // Neukazujeme overlay hned, necháme connectWithRetry, aby ho zobrazil až po selhání pokusu
            } else {
                _errorMessage.value = "An unexpected error occurred: ${exception.message}"
            }
        }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob() + exceptionHandler)

    // UI State
    private val _clients = MutableStateFlow<List<ClientDto>>(emptyList())
    val clients: StateFlow<List<ClientDto>> = _clients.asStateFlow()

    private val _projects = MutableStateFlow<List<ProjectDto>>(emptyList())
    val projects: StateFlow<List<ProjectDto>> = _projects.asStateFlow()

    private val _selectedClientId = MutableStateFlow<String?>(defaultClientId)
    val selectedClientId: StateFlow<String?> = _selectedClientId.asStateFlow()

    private val _selectedProjectId = MutableStateFlow<String?>(defaultProjectId)
    val selectedProjectId: StateFlow<String?> = _selectedProjectId.asStateFlow()

    private val _chatMessages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val chatMessages: StateFlow<List<ChatMessage>> = _chatMessages.asStateFlow()

    private val _inputText = MutableStateFlow("")
    val inputText: StateFlow<String> = _inputText.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _showReconnectDialog = MutableStateFlow(false)
    val showReconnectDialog: StateFlow<Boolean> = _showReconnectDialog.asStateFlow()

    private var pendingMessage: String? = null

    // Connection state
    enum class ConnectionState { DISCONNECTED, CONNECTING, CONNECTED, RECONNECTING }

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _isOverlayVisible = MutableStateFlow(false)
    val isOverlayVisible: StateFlow<Boolean> = _isOverlayVisible.asStateFlow()

    private val _isInitialLoading = MutableStateFlow(true)
    val isInitialLoading: StateFlow<Boolean> = _isInitialLoading.asStateFlow()

    private val _reconnectAttemptDisplay = MutableStateFlow(0)
    val reconnectAttemptDisplay: StateFlow<Int> = _reconnectAttemptDisplay.asStateFlow()

    private var pingJob: Job? = null

    private val _notifications = MutableStateFlow<List<com.jervis.dto.events.JervisEvent>>(emptyList())
    val notifications: StateFlow<List<com.jervis.dto.events.JervisEvent>> = _notifications.asStateFlow()

    private val _queueSize = MutableStateFlow(0)
    val queueSize: StateFlow<Int> = _queueSize.asStateFlow()

    private val _runningProjectId = MutableStateFlow<String?>(null)
    val runningProjectId: StateFlow<String?> = _runningProjectId.asStateFlow()

    private val _runningProjectName = MutableStateFlow<String?>(null)
    val runningProjectName: StateFlow<String?> = _runningProjectName.asStateFlow()

    private val _runningTaskPreview = MutableStateFlow<String?>(null)
    val runningTaskPreview: StateFlow<String?> = _runningTaskPreview.asStateFlow()

    private var chatJob: Job? = null
    private var eventJob: Job? = null
    private var queueStatusJob: Job? = null
    private var reconnectAttempts = 0
    private val maxReconnectAttempts = 50 // Avoid infinite loop if something is fundamentally wrong
    private var isReconnecting = false

    init {
        // Load initial data
        loadClients()

        // Auto-load projects if client is pre-selected
        _selectedClientId.value?.let { clientId ->
            selectClient(clientId)
        }

        // Subscribe to chat stream when both client and project are selected
        scope.launch {
            combine(_selectedClientId, _selectedProjectId) { clientId, projectId ->
                clientId to projectId
            }.collect { (clientId, projectId) ->
                if (clientId != null && projectId != null) {
                    subscribeToChatStream(clientId, projectId)
                }
            }
        }

        // Subscribe to global events for the selected client
        scope.launch {
            _selectedClientId.collect { clientId ->
                if (clientId != null) {
                    subscribeToEventStream(clientId)
                    subscribeToQueueStatus(clientId)
                } else {
                    eventJob?.cancel()
                    queueStatusJob?.cancel()
                }
            }
        }
    }

    private fun subscribeToEventStream(clientId: String) {
        eventJob?.cancel()
        eventJob =
            scope.launch {
                repository.notifications
                    .subscribeToEvents(clientId)
                    .retryWhen { cause, attempt ->
                        println("Event stream error: ${cause.message}, retry attempt $attempt")
                        val delaySeconds =
                            when (attempt + 1) {
                                1L -> 1L
                                2L -> 2L
                                else -> 3L
                            }
                        delay(delaySeconds.seconds)
                        true
                    }.collect { event ->
                        handleGlobalEvent(event)
                    }
            }
    }

    private fun handleGlobalEvent(event: com.jervis.dto.events.JervisEvent) {
        println("Received global event: ${event::class.simpleName}")
        val currentNotifications = _notifications.value
        when (event) {
            is com.jervis.dto.events.JervisEvent.UserTaskCreated -> {
                // UI update or notification
                _notifications.value = currentNotifications + (event as com.jervis.dto.events.JervisEvent)
            }

            is com.jervis.dto.events.JervisEvent.UserTaskCancelled -> {
                // Remove from notifications if needed or just let it be
                _notifications.value =
                    currentNotifications.filter {
                        !(it is com.jervis.dto.events.JervisEvent.UserTaskCreated && it.taskId == event.taskId)
                    }
            }

            is com.jervis.dto.events.JervisEvent.ErrorNotification -> {
                _errorMessage.value = "Server error: ${event.message}"
            }

            else -> {
                // Ignore others or handle as needed
            }
        }
    }

    private fun subscribeToQueueStatus(clientId: String) {
        queueStatusJob?.cancel()
        queueStatusJob =
            scope.launch {
                repository.agentOrchestrator
                    .subscribeToQueueStatus(clientId)
                    .retryWhen { cause, attempt ->
                        println("Queue status stream error: ${cause.message}, retry attempt $attempt")
                        delay(2.seconds)
                        true
                    }.collect { response ->
                        if (response.type == ChatResponseType.QUEUE_STATUS) {
                            _queueSize.value = response.metadata["queueSize"]?.toIntOrNull() ?: 0
                            _runningProjectId.value = response.metadata["runningProjectId"]
                            _runningProjectName.value = response.metadata["runningProjectName"]
                            _runningTaskPreview.value = response.metadata["runningTaskPreview"]
                        }
                    }
            }
    }

    private fun subscribeToChatStream(
        clientId: String,
        projectId: String,
    ) {
        // Cancel previous chat subscription
        chatJob?.cancel()
        pingJob?.cancel()
        reconnectAttempts = 0
        _reconnectAttemptDisplay.value = 0
        isReconnecting = false

        // Subscribe with auto-reconnect
        chatJob =
            scope.launch {
                // Ensure UI reflects connecting state immediately
                _connectionState.value = ConnectionState.CONNECTING
                // Nechceme zobrazit overlay okamžitě při startu, pokud už jsme připojený k něčemu jinému
                // nebo pokud jde o první načtení. Zobrazíme ho až při skutečném výpadku nebo delším čekání.
                connectWithRetry(clientId, projectId)
            }

        // Start ping job
        startPingJob(clientId)
    }

    private fun startPingJob(clientId: String) {
        pingJob?.cancel()
        pingJob =
            scope.launch {
                while (true) {
                    delay(10000) // Reduced frequency to 10 seconds
                    if (_connectionState.value == ConnectionState.CONNECTED) {
                        try {
                            // Simple RPC call to verify connection
                            repository.clients.getClientById(clientId)
                        } catch (e: Exception) {
                            if (e is CancellationException) throw e
                            println("Ping failed: ${e.message}")

                            // If we are DISCONNECTED or it's a "Cancelled" error, trigger reconnect
                            val isRpcCancelled =
                                e.message?.contains("RpcClient was cancelled", ignoreCase = true) == true ||
                                    e.message?.contains("Client cancelled", ignoreCase = true) == true ||
                                    e.message?.contains("connection", ignoreCase = true) == true

                            if (isRpcCancelled) {
                                println("Triggering reconnect due to ping failure or RPC cancellation")
                                _connectionState.value = ConnectionState.DISCONNECTED

                                // Explicitly cancel and restart to force fresh RPC client state
                                chatJob?.cancel()
                                val projectId = _selectedProjectId.value
                                if (projectId != null) {
                                    subscribeToChatStream(clientId, projectId)
                                }
                            }
                        }
                    }
                }
            }
    }

    private suspend fun connectWithRetry(
        clientId: String,
        projectId: String,
    ) {
        while (reconnectAttempts < maxReconnectAttempts) {
            try {
                // If we are already connected and it fails, don't show overlay immediately
                // but if we are RECONNECTING, we might want to show it.
                // Logic was: if error occurs in catch, _isOverlayVisible.value = true

                val state = if (reconnectAttempts > 0) ConnectionState.RECONNECTING else ConnectionState.CONNECTING
                _connectionState.value = state
                _reconnectAttemptDisplay.value = reconnectAttempts + 1

                // Pokud už je to několikátý pokus nebo trvá připojení dlouho, ukážeme overlay
                if (reconnectAttempts > 0) {
                    _isOverlayVisible.value = true

                    // Pokud už máme hodně neúspěšných pokusů, zkusíme kompletní refresh klienta
                    if (reconnectAttempts % 10 == 0 && onRefreshConnection != null) {
                        println("=== Max attempts reached for this RPC client, triggering full refresh ===")
                        onRefreshConnection.invoke()
                        return // LaunchedEffect v JervisApp se postará o zbytek
                    }
                } else {
                    // Při prvním pokusu (CONNECTING) overlay skryjeme, pokud by náhodou visel
                    _isOverlayVisible.value = false
                }

                println(
                    "=== ${state.name}: attempt ${reconnectAttempts + 1}/$maxReconnectAttempts for client=$clientId, project=$projectId ===",
                )

                repository.agentOrchestrator
                    .subscribeToChat(clientId, projectId)
                    .onStart {
                        println("=== Flow started, setting CONNECTED ===")
                        _connectionState.value = ConnectionState.CONNECTED

                        // Po připojení explicitně vyžádat historii, aby UI bylo synchronizované
                        // Reload history se postará o skrytí overlaye
                        scope.launch {
                            reloadHistory(clientId, projectId)
                        }
                    }.catch { e ->
                        println("Chat stream error: ${e.message}")
                        e.printStackTrace()

                        // Check if RPC client was cancelled - this needs full reconnect
                        val isRpcCancelled =
                            e.message?.contains("RpcClient was cancelled", ignoreCase = true) == true ||
                                e.message?.contains("Client cancelled", ignoreCase = true) == true

                        if (isRpcCancelled && reconnectAttempts >= 2) {
                            println("=== RPC client cancelled, triggering full reconnect ===")
                            onRefreshConnection?.invoke()
                            throw e // Stop retry loop, onRefreshConnection will handle it
                        }

                        // Treat as disconnected
                        _connectionState.value = ConnectionState.DISCONNECTED
                        _isOverlayVisible.value = true

                        // Auto-reconnect with custom backoff: 1s, 2s, then stays at 3s
                        reconnectAttempts++
                        _reconnectAttemptDisplay.value = reconnectAttempts
                        val delaySeconds =
                            when (reconnectAttempts) {
                                1 -> 1L
                                else -> 2L
                            }
                        println("=== Reconnecting in ${delaySeconds}s (attempt $reconnectAttempts) ===")
                        delay(delaySeconds.seconds)
                    }.collect { response ->
                        handleChatResponse(response, clientId, projectId)
                    }

                // If flow completes normally, check if it was truly finished or just closed
                println("=== Flow completed normally ===")
                _connectionState.value = ConnectionState.DISCONNECTED

                reconnectAttempts++
                _reconnectAttemptDisplay.value = reconnectAttempts
                val delaySeconds =
                    when (reconnectAttempts) {
                        1 -> 1L
                        else -> 2L
                    }
                println("=== Reconnecting after flow completion in ${delaySeconds}s (attempt $reconnectAttempts) ===")
                delay(delaySeconds.seconds)
            } catch (e: Exception) {
                if (e is CancellationException) {
                    println("=== connectWithRetry cancelled ===")
                    throw e
                }

                println("=== Unexpected error in connectWithRetry: ${e.message} ===")
                e.printStackTrace()

                _connectionState.value = ConnectionState.DISCONNECTED
                _isOverlayVisible.value = true

                reconnectAttempts++
                _reconnectAttemptDisplay.value = reconnectAttempts
                val delaySeconds =
                    when (reconnectAttempts) {
                        1 -> 1L
                        else -> 2L
                    }
                println("=== Reconnecting after error in ${delaySeconds}s (attempt $reconnectAttempts) ===")
                delay(delaySeconds.seconds)
            }
        }
    }

    private suspend fun handleChatResponse(
        response: com.jervis.dto.ChatResponseDto,
        clientId: String,
        projectId: String,
    ) {
        // Filter out internal markers (HISTORY_LOADED, QUEUE_STATUS)
        if (response.metadata["status"] == "synchronized" || response.type == ChatResponseType.QUEUE_STATUS) {
            return
        }

        val messageType =
            when (response.type) {
                ChatResponseType.USER_MESSAGE -> {
                    ChatMessage.MessageType.USER_MESSAGE
                }

                ChatResponseType.PLANNING,
                ChatResponseType.EVIDENCE_GATHERING,
                ChatResponseType.EXECUTING,
                ChatResponseType.REVIEWING,
                -> {
                    ChatMessage.MessageType.PROGRESS
                }

                ChatResponseType.FINAL -> {
                    ChatMessage.MessageType.FINAL
                }

                ChatResponseType.CHAT_CHANGED -> {
                    println("=== Chat session changed, reloading history... ===")
                    reloadHistory(clientId, projectId)
                    null
                }

                else -> {
                    null
                }
            }

        if (messageType == null) return

        println("=== Received chat message (${response.type}): ${response.message.take(100)} ===")

        val messages = _chatMessages.value.toMutableList()

        when (messageType) {
            ChatMessage.MessageType.USER_MESSAGE -> {
                messages.add(
                    ChatMessage(
                        from = ChatMessage.Sender.Me,
                        text = response.message,
                        contextId = projectId,
                        messageType = ChatMessage.MessageType.USER_MESSAGE,
                        metadata = response.metadata,
                        timestamp = response.metadata["timestamp"],
                    ),
                )
            }

            ChatMessage.MessageType.PROGRESS -> {
                if (messages.lastOrNull()?.messageType == ChatMessage.MessageType.PROGRESS) {
                    messages[messages.lastIndex] =
                        ChatMessage(
                            from = ChatMessage.Sender.Assistant,
                            text = response.message,
                            contextId = projectId,
                            messageType = messageType,
                            metadata = response.metadata,
                        )
                } else {
                    messages.add(
                        ChatMessage(
                            from = ChatMessage.Sender.Assistant,
                            text = response.message,
                            contextId = projectId,
                            messageType = messageType,
                            metadata = response.metadata,
                        ),
                    )
                }
            }

            ChatMessage.MessageType.FINAL -> {
                if (messages.lastOrNull()?.messageType == ChatMessage.MessageType.PROGRESS) {
                    messages.removeAt(messages.lastIndex)
                }
                messages.add(
                    ChatMessage(
                        from = ChatMessage.Sender.Assistant,
                        text = response.message,
                        contextId = projectId,
                        messageType = messageType,
                        metadata = response.metadata,
                    ),
                )
            }
        }
        _chatMessages.value = messages
    }

    private suspend fun reloadHistory(
        clientId: String,
        projectId: String,
    ) {
        try {
            val history = repository.agentOrchestrator.getChatHistory(clientId, projectId, limit = 10)
            val newMessages =
                history.messages.map { msg ->
                    ChatMessage(
                        from = if (msg.role == "user") ChatMessage.Sender.Me else ChatMessage.Sender.Assistant,
                        text = msg.content,
                        contextId = projectId,
                        messageType = if (msg.role == "user") ChatMessage.MessageType.USER_MESSAGE else ChatMessage.MessageType.FINAL,
                        metadata = emptyMap(),
                        timestamp = msg.timestamp,
                    )
                }
            _chatMessages.value = newMessages

            // Re-sync UI state
            _connectionState.value = ConnectionState.CONNECTED

            // Důležité: Skrýt overlay okamžitě, jakmile máme data
            _isOverlayVisible.value = false
            reconnectAttempts = 0
            _reconnectAttemptDisplay.value = 0
        } catch (e: Exception) {
            println("Failed to reload history: ${e.message}")

            // Check if RPC client was cancelled - trigger full reconnect
            val isRpcCancelled =
                e.message?.contains("RpcClient was cancelled", ignoreCase = true) == true ||
                    e.message?.contains("Client cancelled", ignoreCase = true) == true

            if (isRpcCancelled) {
                println("=== RPC client cancelled in reloadHistory, triggering full reconnect ===")
                onRefreshConnection?.invoke()
            }
        }
    }

    fun loadClients() {
        scope.launch {
            _isLoading.value = true
            _isInitialLoading.value = true
            try {
                val clientList = repository.clients.getAllClients()
                _clients.value = clientList
            } catch (e: Exception) {
                _errorMessage.value = "Failed to load clients: ${e.message}"
            } finally {
                _isLoading.value = false
                _isInitialLoading.value = false
            }
        }
    }

    fun selectClient(clientId: String) {
        if (_selectedClientId.value == clientId) return

        _selectedClientId.value = clientId
        _selectedProjectId.value = null // Reset project selection temporarily
        _projects.value = emptyList()
        _chatMessages.value = emptyList() // Clear messages for the new client

        chatJob?.cancel() // Zrušit stream pro předchozího klienta
        pingJob?.cancel()
        _connectionState.value = ConnectionState.DISCONNECTED
        _isOverlayVisible.value = false // Skrýt overlay při změně klienta

        scope.launch {
            _isLoading.value = true
            try {
                val projectList = repository.projects.listProjectsForClient(clientId)
                _projects.value = projectList

                // Restore last selected project if available
                val client = _clients.value.find { it.id == clientId }
                client?.lastSelectedProjectId?.let { lastProjectId ->
                    if (projectList.any { it.id == lastProjectId }) {
                        _selectedProjectId.value = lastProjectId
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _errorMessage.value = "Failed to load projects: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun selectProject(projectId: String) {
        if (projectId.isBlank()) {
            _selectedProjectId.value = null
            return
        }
        if (_selectedProjectId.value == projectId) return

        _selectedProjectId.value = projectId
        _chatMessages.value = emptyList() // Clear messages for the new project

        // Zrušit předchozí stream a nechat init bloku v kombinaci s MutableStateFlows spustit nový
        chatJob?.cancel()
        pingJob?.cancel()
        _connectionState.value = ConnectionState.DISCONNECTED
        _isOverlayVisible.value = false // Skrýt overlay při změně projektu

        // Save selection to server
        val clientId = _selectedClientId.value
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

    fun updateInputText(text: String) {
        _inputText.value = text
    }

    fun sendMessage() {
        val text = _inputText.value.trim()
        if (text.isEmpty()) return

        val clientId = _selectedClientId.value
        val projectId = _selectedProjectId.value

        if (clientId == null) {
            _errorMessage.value = "Nejdříve vyberte klienta"
            return
        }

        // DON'T add user message locally - it will arrive via subscribeToChat stream
        // This ensures synchronization across all clients (Desktop/iOS/Android)

        // Send message - user message + responses will arrive via subscribeToChat stream
        scope.launch {
            _isLoading.value = true
            val originalText = text
            _inputText.value = "" // Clear input immediately

            try {
                repository.agentOrchestrator.sendMessage(
                    ChatRequestDto(
                        text = originalText,
                        context =
                            ChatRequestContextDto(
                                clientId = clientId,
                                projectId = projectId,
                            ),
                    ),
                )
                println("=== Message sent successfully ===")
                pendingMessage = null
            } catch (e: Exception) {
                println("Error sending message: ${e.message}")
                e.printStackTrace()
                pendingMessage = originalText
                _errorMessage.value = "Nepodařilo se odeslat zprávu: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun retrySendMessage() {
        val text = pendingMessage ?: return
        val clientId = _selectedClientId.value
        val projectId = _selectedProjectId.value

        if (clientId == null) return

        scope.launch {
            _isLoading.value = true
            try {
                repository.agentOrchestrator.sendMessage(
                    ChatRequestDto(
                        text = text,
                        context =
                            ChatRequestContextDto(
                                clientId = clientId,
                                projectId = projectId,
                            ),
                    ),
                )
                println("=== Retried message sent successfully ===")
                pendingMessage = null
            } catch (e: Exception) {
                println("Error retrying message: ${e.message}")
                _errorMessage.value = "Nepodařilo se odeslat zprávu: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun cancelRetry() {
        pendingMessage = null
    }

    fun clearError() {
        _errorMessage.value = null
    }

    fun onDispose() {
        // Cleanup if needed
    }
}
