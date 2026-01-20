package com.jervis.ui

import com.jervis.dto.ClientDto
import com.jervis.dto.ProjectDto
import com.jervis.dto.ui.ChatMessage
import com.jervis.repository.JervisRepository
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

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

    private var chatJob: kotlinx.coroutines.Job? = null

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
    }

    private fun subscribeToChatStream(clientId: String, projectId: String) {
        // Cancel previous chat subscription
        chatJob?.cancel()

        // Clear current chat messages
        _chatMessages.value = emptyList()

        // Load chat history
        scope.launch {
            try {
                println("=== Loading chat history for client=$clientId, project=$projectId ===")
                val history = repository.agentChat.getChatHistory(clientId, projectId, limit = 10)
                _chatMessages.value = history.messages.map { msg ->
                    ChatMessage(
                        from = when (msg.role) {
                            "user" -> ChatMessage.Sender.Me
                            else -> ChatMessage.Sender.Assistant
                        },
                        text = msg.content,
                        contextId = projectId,
                        timestamp = msg.timestamp,
                        messageType = ChatMessage.MessageType.FINAL, // History messages are always FINAL
                        metadata = emptyMap(),
                    )
                }
                println("=== Loaded ${history.messages.size} messages from history ===")
            } catch (e: Exception) {
                println("Failed to load chat history: ${e.message}")
                e.printStackTrace()
            }
        }

        // Subscribe to long-lived chat stream
        chatJob = scope.launch {
            println("=== Subscribing to chat stream for client=$clientId, project=$projectId ===")
            repository.agentChat.subscribeToChat(clientId, projectId)
                .catch { e ->
                    println("Chat stream error: ${e.message}")
                    e.printStackTrace()
                    _errorMessage.value = "Chat connection error: ${e.message}"
                }
                .collect { response ->
                    val messageType = when (response.type) {
                        com.jervis.dto.ChatResponseType.PROGRESS -> ChatMessage.MessageType.PROGRESS
                        com.jervis.dto.ChatResponseType.FINAL -> ChatMessage.MessageType.FINAL
                    }

                    println("=== Received chat message (${response.type}): ${response.message.take(100)} ===")

                    // For PROGRESS messages, either update last progress or add new
                    // For FINAL messages, always add new
                    if (messageType == ChatMessage.MessageType.PROGRESS) {
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

        // Add user message to chat
        _chatMessages.value = _chatMessages.value +
            ChatMessage(
                from = ChatMessage.Sender.Me,
                text = text,
                contextId = projectId,
            )

        // Send message - responses will arrive via subscribeToChat stream
        scope.launch {
            _isLoading.value = true
            _inputText.value = "" // Clear input immediately

            try {
                repository.agentChat.sendMessage(
                    text = text,
                    clientId = clientId,
                    projectId = projectId,
                )
                println("=== Message sent successfully ===")
            } catch (e: Exception) {
                println("Error sending message: ${e.message}")
                e.printStackTrace()
                _errorMessage.value = "Failed to send message: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun clearError() {
        _errorMessage.value = null
    }

    fun onDispose() {
        // Cleanup if needed
    }
}
