package com.jervis.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jervis.dto.ClientDto
import com.jervis.dto.ProjectDto
import com.jervis.repository.JervisRepository
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Main ViewModel - Simplified for initial setup
 * Uses repository with built-in error handling to prevent crashes
 */
class MainViewModel(
    private val repository: JervisRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    // Global exception handler to prevent app crashes
    private val exceptionHandler =
        CoroutineExceptionHandler { _, exception ->
            println("Uncaught exception in MainViewModel: ${exception.message}")
            exception.printStackTrace()
            _uiState.value = _uiState.value.copy(
                isLoading = false,
                error = "Failed to load data: ${exception.message}",
            )
        }

    init {
        loadData()
    }

    fun loadData() {
        viewModelScope.launch(exceptionHandler) {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)

            try {
                val clients = repository.clients.listClients()
                val projects = repository.projects.getAllProjects()

                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    clients = clients,
                    projects = projects,
                )
            } catch (e: Exception) {
                println("Error in loadData: ${e.message}")
                e.printStackTrace()
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = e.message ?: "Unknown error",
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
