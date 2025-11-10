package com.jervis.ui

import com.jervis.dto.ClientDto
import com.jervis.dto.ProjectDto
import com.jervis.dto.ui.ChatMessage
import com.jervis.dto.ui.MobileBootstrap
import com.jervis.repository.JervisRepository
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
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

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

    init {
        // Load initial data
        loadClients()

        // Auto-load projects if client is pre-selected
        _selectedClientId.value?.let { clientId ->
            loadProjectsForClient(clientId)
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
        _selectedProjectId.value = null // Reset project selection
        _projects.value = emptyList()
        loadProjectsForClient(clientId)
    }

    private fun loadProjectsForClient(clientId: String) {
        scope.launch {
            _isLoading.value = true
            try {
                val projectList = repository.projects.listProjectsForClient(clientId)
                _projects.value = projectList
            } catch (e: Exception) {
                _errorMessage.value = "Failed to load projects: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun selectProject(projectId: String) {
        _selectedProjectId.value = projectId
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
        _chatMessages.value = _chatMessages.value + ChatMessage(
            from = ChatMessage.Sender.Me,
            text = text,
            contextId = projectId
        )

        scope.launch {
            _isLoading.value = true
            _inputText.value = "" // Clear input immediately

            try {
                // Send message to agent orchestrator
                repository.agentChat.sendMessage(
                    text = text,
                    clientId = clientId,
                    projectId = projectId,
                    wsSessionId = null
                )

                // Add confirmation message
                _chatMessages.value = _chatMessages.value + ChatMessage(
                    from = ChatMessage.Sender.Assistant,
                    text = "Message sent to agent orchestrator. Monitor via notifications.",
                    contextId = projectId
                )
            } catch (e: Exception) {
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
