package com.jervis.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jervis.dto.ClientDto
import com.jervis.dto.ProjectDto
import com.jervis.service.IClientProjectLinkService
import com.jervis.service.IClientService
import com.jervis.service.IProjectService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Main ViewModel - Simplified for initial setup
 * TODO: Add full chat functionality after testing
 */
class MainViewModel(
    private val projectService: IProjectService,
    private val clientService: IClientService,
    private val clientProjectLinkService: IClientProjectLinkService
) : ViewModel() {

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    init {
        loadData()
    }

    fun loadData() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)

            try {
                val clients = clientService.getAllClients()
                val projects = projectService.getAllProjects()

                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    clients = clients,
                    projects = projects
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = e.message ?: "Unknown error"
                )
            }
        }
    }
}

data class MainUiState(
    val isLoading: Boolean = false,
    val error: String? = null,
    val clients: List<ClientDto> = emptyList(),
    val projects: List<ProjectDto> = emptyList()
)
