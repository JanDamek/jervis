package com.jervis.mobile.ui

import com.jervis.dto.ClientDto
import com.jervis.dto.ProjectDto
import com.jervis.mobile.ChatMessage
import com.jervis.mobile.MobileBootstrap
import com.jervis.mobile.api.KtorMobileAppFacade
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/**
 * ViewModel for MainScreen
 * Manages state and business logic for the mobile UI
 */
class MainViewModel(
    bootstrap: MobileBootstrap,
) {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val facade = KtorMobileAppFacade(bootstrap)

    // UI State
    private val _clients = MutableStateFlow<List<ClientDto>>(emptyList())
    val clients: StateFlow<List<ClientDto>> = _clients.asStateFlow()

    private val _projects = MutableStateFlow<List<ProjectDto>>(emptyList())
    val projects: StateFlow<List<ProjectDto>> = _projects.asStateFlow()

    private val _selectedClientId = MutableStateFlow<String?>(bootstrap.clientId.takeIf { it.isNotBlank() })
    val selectedClientId: StateFlow<String?> = _selectedClientId.asStateFlow()

    private val _selectedProjectId = MutableStateFlow<String?>(bootstrap.defaultProjectId)
    val selectedProjectId: StateFlow<String?> = _selectedProjectId.asStateFlow()

    private val _chatMessages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val chatMessages: StateFlow<List<ChatMessage>> = _chatMessages.asStateFlow()

    private val _inputText = MutableStateFlow("")
    val inputText: StateFlow<String> = _inputText.asStateFlow()

    val isLoading: StateFlow<Boolean> = facade.isLoading

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    init {
        // Start notifications
        facade.startNotifications()

        // Collect chat messages from facade
        scope.launch {
            facade.chat.collect { message ->
                _chatMessages.value = _chatMessages.value + message
            }
        }

        // Load initial data
        loadClients()

        // Auto-load projects if client is pre-selected
        _selectedClientId.value?.let { clientId ->
            loadProjectsForClient(clientId)
        }
    }

    fun loadClients() {
        scope.launch {
            facade
                .listClients()
                .onSuccess { _clients.value = it }
                .onFailure { _errorMessage.value = "Failed to load clients: ${it.message}" }
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
            facade
                .listProjectsForClient(clientId)
                .onSuccess { _projects.value = it }
                .onFailure { _errorMessage.value = "Failed to load projects: ${it.message}" }
        }
    }

    fun selectProject(projectId: String) {
        _selectedProjectId.value = projectId
        val clientId = _selectedClientId.value ?: return
        facade.select(clientId, projectId)
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

        scope.launch {
            facade
                .sendChat(text, quick = false)
                .onSuccess {
                    _inputText.value = "" // Clear input on success
                }.onFailure {
                    _errorMessage.value = "Failed to send message: ${it.message}"
                }
        }
    }

    fun clearError() {
        _errorMessage.value = null
    }

    fun onDispose() {
        facade.stopNotifications()
        facade.close()
    }
}
