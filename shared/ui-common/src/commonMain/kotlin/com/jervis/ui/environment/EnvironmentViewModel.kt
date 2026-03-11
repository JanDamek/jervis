package com.jervis.ui.environment

import com.jervis.dto.environment.EnvironmentDto
import com.jervis.dto.environment.EnvironmentStateEnum
import com.jervis.dto.environment.EnvironmentStatusDto
import com.jervis.repository.JervisRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * ViewModel for K8s environment panel — status, polling, expand/collapse.
 *
 * Observes selectedClientId/selectedProjectId from MainViewModel (read-only StateFlows)
 * to know which client's environments to load.
 */
class EnvironmentViewModel(
    private val repository: JervisRepository,
    private val selectedClientId: StateFlow<String?>,
    private val selectedProjectId: StateFlow<String?>,
) {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val _environments = MutableStateFlow<List<EnvironmentDto>>(emptyList())
    val environments: StateFlow<List<EnvironmentDto>> = _environments.asStateFlow()

    private val _resolvedEnvId = MutableStateFlow<String?>(null)
    val resolvedEnvId: StateFlow<String?> = _resolvedEnvId.asStateFlow()

    private val _environmentStatuses = MutableStateFlow<Map<String, EnvironmentStatusDto>>(emptyMap())
    val environmentStatuses: StateFlow<Map<String, EnvironmentStatusDto>> = _environmentStatuses.asStateFlow()

    private val _panelVisible = MutableStateFlow(false)
    val panelVisible: StateFlow<Boolean> = _panelVisible.asStateFlow()

    private val _panelWidthFraction = MutableStateFlow(0.35f)
    val panelWidthFraction: StateFlow<Float> = _panelWidthFraction.asStateFlow()

    private val _expandedEnvIds = MutableStateFlow<Set<String>>(emptySet())
    val expandedEnvIds: StateFlow<Set<String>> = _expandedEnvIds.asStateFlow()

    private val _expandedComponentIds = MutableStateFlow<Set<String>>(emptySet())
    val expandedComponentIds: StateFlow<Set<String>> = _expandedComponentIds.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /** Log viewer state: environmentId + componentName + logs content */
    data class LogViewState(val envId: String, val componentName: String, val logs: String? = null)

    private val _logView = MutableStateFlow<LogViewState?>(null)
    val logView: StateFlow<LogViewState?> = _logView.asStateFlow()

    /** ID of the environment the user last expanded/selected in the panel. */
    private val _selectedEnvironmentId = MutableStateFlow<String?>(null)
    val selectedEnvironmentId: StateFlow<String?> = _selectedEnvironmentId.asStateFlow()

    val hasEnvironment: Boolean get() = _environments.value.isNotEmpty()

    /**
     * Returns the "active" environment ID for chat context purposes.
     * Priority: resolvedEnvId (auto-detected from project) > selectedEnvironmentId (user click).
     */
    val activeEnvironmentId: String?
        get() = _resolvedEnvId.value ?: _selectedEnvironmentId.value

    /**
     * Returns a short summary of the active environment for chat context.
     * Used by ChatViewModel to inform the orchestrator about the current environment.
     */
    fun getActiveEnvironmentSummary(): String? {
        val envId = activeEnvironmentId ?: return null
        val env = _environments.value.find { it.id == envId } ?: return null
        val status = _environmentStatuses.value[envId]
        val state = status?.state ?: env.state
        val componentNames = env.components.joinToString(", ") { "${it.name}(${it.type.name})" }
        val mappingCount = env.propertyMappings.size
        return buildString {
            append("name=${env.name}, namespace=${env.namespace}, state=$state")
            if (env.components.isNotEmpty()) append(", components=[$componentNames]")
            if (mappingCount > 0) append(", propertyMappings=$mappingCount")
        }
    }

    private var pollingJob: Job? = null

    fun loadEnvironments(clientId: String) {
        scope.launch {
            _loading.value = true
            _error.value = null
            try {
                val envList = repository.environments.listEnvironments(clientId)
                _environments.value = envList
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _error.value = "Nepodařilo se načíst prostředí: ${e.message}"
            } finally {
                _loading.value = false
            }
        }
    }

    fun resolveEnvironment(projectId: String) {
        scope.launch {
            try {
                val env = repository.environments.resolveEnvironmentForProject(projectId)
                _resolvedEnvId.value = env?.id
                if (env != null) {
                    _expandedEnvIds.value = _expandedEnvIds.value + env.id
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _resolvedEnvId.value = null
            }
        }
    }

    fun togglePanel() {
        val newVisible = !_panelVisible.value
        _panelVisible.value = newVisible
        if (newVisible) {
            val clientId = selectedClientId.value?.takeIf { it != "__global__" }
            if (clientId != null && _environments.value.isEmpty()) {
                loadEnvironments(clientId)
            }
            val projectId = selectedProjectId.value
            if (projectId != null && _resolvedEnvId.value == null) {
                resolveEnvironment(projectId)
            }
            startPolling()
        } else {
            stopPolling()
        }
    }

    fun closePanel() {
        _panelVisible.value = false
        stopPolling()
    }

    fun updatePanelWidthFraction(fraction: Float) {
        _panelWidthFraction.value = fraction
    }

    fun toggleEnvExpanded(envId: String) {
        val current = _expandedEnvIds.value
        val willExpand = !current.contains(envId)
        _expandedEnvIds.value = if (willExpand) current + envId else current - envId
        // Track last expanded environment for chat context
        if (willExpand) {
            _selectedEnvironmentId.value = envId
        }
    }

    fun toggleComponentExpanded(componentId: String) {
        val current = _expandedComponentIds.value
        _expandedComponentIds.value = if (current.contains(componentId)) current - componentId else current + componentId
    }

    fun refreshEnvironments() {
        val clientId = selectedClientId.value?.takeIf { it != "__global__" } ?: return
        loadEnvironments(clientId)
        pollStatuses()
    }

    fun deployEnvironment(envId: String) {
        scope.launch {
            try {
                repository.environments.provisionEnvironment(envId)
                refreshEnvironments()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _error.value = "Deploy selhal: ${e.message}"
            }
        }
    }

    fun stopEnvironment(envId: String) {
        scope.launch {
            try {
                repository.environments.deprovisionEnvironment(envId)
                refreshEnvironments()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _error.value = "Zastavení selhalo: ${e.message}"
            }
        }
    }

    fun viewComponentLogs(envId: String, componentName: String) {
        _logView.value = LogViewState(envId, componentName)
        scope.launch {
            try {
                val logs = repository.environments.getComponentLogs(envId, componentName)
                _logView.value = LogViewState(envId, componentName, logs)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _logView.value = LogViewState(envId, componentName, "Chyba: ${e.message}")
            }
        }
    }

    fun closeLogView() {
        _logView.value = null
    }

    /**
     * Reset state when client changes. Called from MainViewModel.selectClient().
     */
    fun resetForClient() {
        _environments.value = emptyList()
        _resolvedEnvId.value = null
        _selectedEnvironmentId.value = null
        _environmentStatuses.value = emptyMap()
        closePanel()
    }

    fun onDispose() {
        stopPolling()
    }

    private fun startPolling() {
        stopPolling()
        pollingJob = scope.launch {
            while (true) {
                pollStatuses()
                delay(30_000)
            }
        }
    }

    private fun stopPolling() {
        pollingJob?.cancel()
        pollingJob = null
    }

    private fun pollStatuses() {
        scope.launch {
            val envs = _environments.value
            val pollableStates = setOf(EnvironmentStateEnum.RUNNING, EnvironmentStateEnum.CREATING)
            val toPoll = envs.filter { it.state in pollableStates }
            val statuses = mutableMapOf<String, EnvironmentStatusDto>()
            statuses.putAll(_environmentStatuses.value)
            toPoll.forEach { env ->
                try {
                    val status = repository.environments.getEnvironmentStatus(env.id)
                    statuses[env.id] = status
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                }
            }
            _environmentStatuses.value = statuses
        }
    }
}
