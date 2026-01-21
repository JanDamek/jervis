package com.jervis.ui

import com.jervis.dto.ClientDto
import com.jervis.dto.ProjectDto
import com.jervis.dto.ui.ChatMessage
import com.jervis.repository.JervisRepository
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlin.math.pow
import kotlin.time.Duration.Companion.seconds

/**
 * ViewModel for MainScreen
 * Manages state and business logic for the UI
 */
class MainViewModel(
    private val repository: JervisRepository,
    defaultClientId: String? = null,
    defaultProjectId: String? = null,
) {
    // Global exception handler to prevent app crashes
    private val exceptionHandler = CoroutineExceptionHandler { _, exception ->
        println("Uncaught exception in MainViewModel: ${exception.message}")
        exception.printStackTrace()
        _errorMessage.value = "An unexpected error occurred: ${exception.message}"
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

    private val _notifications = MutableStateFlow<List<com.jervis.dto.events.JervisEvent>>(emptyList())
    val notifications: StateFlow<List<com.jervis.dto.events.JervisEvent>> = _notifications.asStateFlow()

    private var chatJob: kotlinx.coroutines.Job? = null
    private var eventJob: kotlinx.coroutines.Job? = null
    private var reconnectAttempts = 0
    private val maxReconnectAttempts = 10
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
                } else {
                    eventJob?.cancel()
                }
            }
        }
    }

    private fun subscribeToEventStream(clientId: String) {
        eventJob?.cancel()
        eventJob = scope.launch {
            repository.connection.subscribeToEvents(clientId)
                .retryWhen { cause, attempt ->
                    println("Event stream error: ${cause.message}, retry attempt $attempt")
                    delay(minOf(2.0.pow(attempt.toInt()).toLong(), 30L).seconds)
                    true
                }
                .collect { event ->
                    handleGlobalEvent(event)
                }
        }
    }

    private fun handleGlobalEvent(event: com.jervis.dto.events.JervisEvent) {
        println("Received global event: ${event::class.simpleName}")
        when (event) {
            is com.jervis.dto.events.JervisEvent.UserTaskCreated -> {
                // UI update or notification
                _notifications.value = _notifications.value + event
            }
            is com.jervis.dto.events.JervisEvent.UserDialogRequest -> {
                // Handle dialog request globally (maybe show a system-wide dialog)
                // In this simplified version, we just add it to notifications
                _notifications.value = _notifications.value + event
            }
            is com.jervis.dto.events.JervisEvent.ErrorNotification -> {
                _errorMessage.value = "Server error: ${event.message}"
            }
            else -> {
                // Ignore others or handle as needed
            }
        }
    }

    private fun subscribeToChatStream(clientId: String, projectId: String) {
        // Cancel previous chat subscription
        chatJob?.cancel()
        isReconnecting = false
        reconnectAttempts = 0

        // Clear current chat messages only on first connect
        if (_chatMessages.value.isEmpty()) {
            _chatMessages.value = emptyList()
        }

        // Subscribe with auto-reconnect
        chatJob = scope.launch {
            connectWithRetry(clientId, projectId)
        }
    }

    private suspend fun connectWithRetry(clientId: String, projectId: String) {
        while (reconnectAttempts < maxReconnectAttempts) {
            try {
                val state = if (reconnectAttempts > 0) ConnectionState.RECONNECTING else ConnectionState.CONNECTING
                _connectionState.value = state
                println("=== ${state.name}: attempt ${reconnectAttempts + 1}/$maxReconnectAttempts for client=$clientId, project=$projectId ===")

                repository.agentChat.subscribeToChat(clientId, projectId)
                    .onStart {
                        println("=== Flow started, setting CONNECTED ===")
                        _connectionState.value = ConnectionState.CONNECTED
                        reconnectAttempts = 0 // Reset on successful connection
                    }
                    .catch { e ->
                        println("Chat stream error: ${e.message}")
                        e.printStackTrace()
                        _connectionState.value = ConnectionState.DISCONNECTED

                        // Auto-reconnect with exponential backoff
                        reconnectAttempts++
                        if (reconnectAttempts < maxReconnectAttempts) {
                            val delaySeconds = minOf(2.0.pow(reconnectAttempts).toLong(), 60L)
                            println("=== Reconnecting in ${delaySeconds}s (attempt $reconnectAttempts/$maxReconnectAttempts) ===")
                            delay(delaySeconds.seconds)
                        } else {
                            _errorMessage.value = "Connection failed after $maxReconnectAttempts attempts: ${e.message}"
                            println("=== Max reconnect attempts reached ===")
                        }
                    }
                    .collect { response ->
                    val messageType = when (response.type) {
                        com.jervis.dto.ChatResponseType.USER_MESSAGE -> ChatMessage.MessageType.USER_MESSAGE
                        com.jervis.dto.ChatResponseType.PROGRESS -> ChatMessage.MessageType.PROGRESS
                        com.jervis.dto.ChatResponseType.FINAL -> ChatMessage.MessageType.FINAL
                    }

                    println("=== Received chat message (${response.type}): ${response.message.take(100)} ===")

                    // USER_MESSAGE - Always add as user message (for cross-client sync)
                    if (messageType == ChatMessage.MessageType.USER_MESSAGE) {
                        _chatMessages.value = _chatMessages.value + ChatMessage(
                            from = ChatMessage.Sender.Me,
                            text = response.message,
                            contextId = projectId,
                            messageType = ChatMessage.MessageType.USER_MESSAGE,
                            metadata = response.metadata,
                            timestamp = response.metadata["timestamp"],
                        )
                    } else if (messageType == ChatMessage.MessageType.PROGRESS) {
                        // For PROGRESS messages, either update last progress or add new
                        val messages = _chatMessages.value.toMutableList()
                        // Replace last progress message if exists, otherwise add
                        if (messages.lastOrNull()?.messageType == ChatMessage.MessageType.PROGRESS) {
                            messages[messages.lastIndex] = ChatMessage(
                                from = ChatMessage.Sender.Assistant,
                                text = response.message,
                                contextId = projectId,
                                messageType = messageType,
                                metadata = response.metadata,
                            )
                        } else {
                            messages.add(ChatMessage(
                                from = ChatMessage.Sender.Assistant,
                                text = response.message,
                                contextId = projectId,
                                messageType = messageType,
                                metadata = response.metadata,
                            ))
                        }
                        _chatMessages.value = messages
                    } else {
                        // FINAL message - always add new
                        _chatMessages.value = _chatMessages.value + ChatMessage(
                            from = ChatMessage.Sender.Assistant,
                            text = response.message,
                            contextId = projectId,
                            messageType = messageType,
                            metadata = response.metadata,
                        )
                    }
                }

                // Flow completed normally - connection was terminated
                println("=== Flow completed, connection terminated ===")
                _connectionState.value = ConnectionState.DISCONNECTED
                break // Exit retry loop on normal completion

            } catch (e: Exception) {
                // Unexpected error outside of catch operator
                println("=== Unexpected error in connectWithRetry: ${e.message} ===")
                e.printStackTrace()
                _connectionState.value = ConnectionState.DISCONNECTED
                reconnectAttempts++

                if (reconnectAttempts < maxReconnectAttempts) {
                    val delaySeconds = minOf(2.0.pow(reconnectAttempts).toLong(), 60L)
                    println("=== Reconnecting in ${delaySeconds}s ===")
                    delay(delaySeconds.seconds)
                } else {
                    _errorMessage.value = "Connection failed: ${e.message}"
                    break
                }
            }
        }
    }

    fun loadClients() {
        scope.launch {
            _isLoading.value = true
            try {
                val clientList = repository.clients.listClients()
                _clients.value = clientList
            } catch (e: Exception) {
                _errorMessage.value = "Failed to load clients: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun selectClient(clientId: String) {
        _selectedClientId.value = clientId
        _selectedProjectId.value = null // Reset project selection temporarily
        _projects.value = emptyList()

        // Load projects and restore last selected
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
                _errorMessage.value = "Failed to load projects: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun selectProject(projectId: String) {
        _selectedProjectId.value = projectId

        // Save selection to server
        val clientId = _selectedClientId.value
        if (clientId != null) {
            scope.launch {
                try {
                    val updatedClient = repository.clients.updateLastSelectedProject(clientId, projectId)
                    // Update local cache if successful
                    if (updatedClient != null) {
                        _clients.value =
                            _clients.value.map {
                                if (it.id == clientId) updatedClient else it
                            }
                    }
                } catch (e: Exception) {
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

        if (clientId == null || projectId == null) {
            _errorMessage.value = "Please select a client and project first"
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
                repository.agentChat.sendMessage(
                    text = originalText,
                    clientId = clientId,
                    projectId = projectId,
                )
                println("=== Message sent successfully ===")
                pendingMessage = null
            } catch (e: Exception) {
                println("Error sending message: ${e.message}")
                e.printStackTrace()
                pendingMessage = originalText
                _showReconnectDialog.value = true
                _errorMessage.value = "Failed to send message: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun retrySendMessage() {
        val text = pendingMessage ?: return
        val clientId = _selectedClientId.value
        val projectId = _selectedProjectId.value

        if (clientId == null || projectId == null) return

        _showReconnectDialog.value = false
        
        scope.launch {
            _isLoading.value = true
            try {
                repository.agentChat.sendMessage(
                    text = text,
                    clientId = clientId,
                    projectId = projectId,
                )
                println("=== Retried message sent successfully ===")
                pendingMessage = null
            } catch (e: Exception) {
                println("Error retrying message: ${e.message}")
                _showReconnectDialog.value = true
                _errorMessage.value = "Failed to send message: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun cancelRetry() {
        pendingMessage = null
        _showReconnectDialog.value = false
    }

    fun clearError() {
        _errorMessage.value = null
    }

    fun onDispose() {
        // Cleanup if needed
    }

    fun manualReconnect() {
        val clientId = _selectedClientId.value
        val projectId = _selectedProjectId.value
        if (clientId != null && projectId != null) {
            subscribeToChatStream(clientId, projectId)
        } else {
            loadClients()
        }
    }
}
