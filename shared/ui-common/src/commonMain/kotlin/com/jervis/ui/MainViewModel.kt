package com.jervis.ui

import com.jervis.dto.ClientDto
import com.jervis.dto.ProjectDto
import com.jervis.dto.ui.ChatMessage
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
            selectClient(clientId)
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
                    // Update local cache
                    _clients.value =
                        _clients.value.map {
                            if (it.id == clientId) updatedClient else it
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

        scope.launch {
            _isLoading.value = true
            _inputText.value = "" // Clear input immediately

            try {
                // Send message to agent orchestrator and wait for final answer
                val response = repository.agentChat.sendAndWaitForAnswer(
                    text = text,
                    clientId = clientId,
                    projectId = projectId,
                )

                // Show assistant's final answer prepared by finalizer
                _chatMessages.value = _chatMessages.value +
                    ChatMessage(
                        from = ChatMessage.Sender.Assistant,
                        text = response.message,
                        contextId = projectId,
                    )
            } catch (e: Exception) {
                _errorMessage.value = "Failed to get answer: ${e.message}"
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
