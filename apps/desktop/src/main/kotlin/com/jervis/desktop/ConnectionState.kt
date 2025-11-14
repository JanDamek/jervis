package com.jervis.desktop

import androidx.compose.runtime.*
import com.jervis.di.NetworkModule
import com.jervis.di.createJervisServices
import com.jervis.dto.events.DebugEventDto
import com.jervis.dto.events.ErrorNotificationEventDto
import com.jervis.repository.JervisRepository
import kotlinx.coroutines.*

/**
 * Connection state for the desktop application
 * Handles server connectivity and automatic reconnection
 */
sealed class ConnectionStatus {
    data object Connecting : ConnectionStatus()
    data object Connected : ConnectionStatus()
    data class Disconnected(val error: String) : ConnectionStatus()
    data object Offline : ConnectionStatus()
}

/**
 * Manages connection to Jervis server with automatic retry
 */
class ConnectionManager(private val serverBaseUrl: String) : com.jervis.ui.DebugEventsProvider {
    var status by mutableStateOf<ConnectionStatus>(ConnectionStatus.Connecting)
        private set

    var repository by mutableStateOf<JervisRepository?>(null)
        private set

    var taskBadgeCount by mutableStateOf(0)
        private set

    var errorNotifications by mutableStateOf<List<ErrorNotificationEventDto>>(emptyList())
        private set

    // Expose debug events as Flow instead of accumulated list to avoid duplicates
    val debugWebSocketFlow: kotlinx.coroutines.flow.SharedFlow<DebugEventDto>?
        get() = debugWebSocketClient?.debugEvents

    // Implement DebugEventsProvider interface
    override val debugEventsFlow: kotlinx.coroutines.flow.Flow<com.jervis.dto.events.DebugEventDto>?
        get() = debugWebSocketFlow

    private var services: NetworkModule.Services? = null
    private var webSocketClient: WebSocketClient? = null
    private var debugWebSocketClient: DebugWebSocketClient? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * Initialize connection and start retry loop
     */
    suspend fun connect() {
        while (true) {
            try {
                status = ConnectionStatus.Connecting

                // Try to create services
                services = createJervisServices(serverBaseUrl)

                // Create repository
                repository = JervisRepository(
                    clientService = services!!.clientService,
                    projectService = services!!.projectService,
                    userTaskService = services!!.userTaskService,
                    ragSearchService = services!!.ragSearchService,
                    taskSchedulingService = services!!.taskSchedulingService,
                    agentOrchestratorService = services!!.agentOrchestratorService,
                    errorLogService = services!!.errorLogService,
                    integrationSettingsService = services!!.integrationSettingsService,
                    gitConfigurationService = services!!.gitConfigurationService,
                    jiraSetupService = services!!.jiraSetupService,
                    emailAccountService = services!!.emailAccountService,
                    indexingStatusService = services!!.indexingStatusService,
                    pendingTaskService = services!!.pendingTaskService,
                )

                // Try a simple test call to verify connectivity
                try {
                    repository!!.clients.listClients()
                    status = ConnectionStatus.Connected

                    // Start WebSocket client for real-time notifications
                    startWebSocket()

                    // Update badge with initial task count
                    updateTaskBadge()

                    // Stay connected - don't retry
                    return
                } catch (e: Exception) {
                    status = ConnectionStatus.Disconnected("Server not responding: ${e.message}")
                }

            } catch (e: Exception) {
                status = ConnectionStatus.Disconnected("Failed to initialize: ${e.message}")
            }

            // Wait before retry
            delay(5000)
        }
    }

    /**
     * Start WebSocket client and listen for events
     */
    private suspend fun startWebSocket() {
        webSocketClient = WebSocketClient(serverBaseUrl)
        webSocketClient?.start()

        // Start separate debug WebSocket client
        debugWebSocketClient = DebugWebSocketClient(serverBaseUrl)
        debugWebSocketClient?.start()

        // Listen for user task events
        scope.launch {
            webSocketClient?.userTaskEvents?.collect { event ->
                println("User task created: ${event.title}")
                updateTaskBadge()
                MacOSUtils.showNotification("New Task", event.title)
            }
        }

        // Listen for user task cancelled events
        scope.launch {
            webSocketClient?.userTaskCancelledEvents?.collect { event ->
                println("User task cancelled: ${event.title}")
                updateTaskBadge()
            }
        }

        // Listen for error notifications
        scope.launch {
            webSocketClient?.errorEvents?.collect { event ->
                println("Error: ${event.message}")
                errorNotifications = errorNotifications + event
                MacOSUtils.showNotification("Error", event.message)
            }
        }

        // Debug events are now consumed directly by DebugWindow via debugWebSocketFlow
        // No need to accumulate them here
    }

    /**
     * Update task badge count
     */
    private suspend fun updateTaskBadge() {
        try {
            val repo = repository ?: return
            val clients = repo.clients.listClients()
            var total = 0
            for (client in clients) {
                client.id?.let { clientId ->
                    runCatching {
                        val countDto = repo.userTasks.activeCount(clientId)
                        total += countDto.activeCount
                    }
                }
            }
            taskBadgeCount = total
            MacOSUtils.setDockBadgeCount(total)
        } catch (e: Exception) {
            println("Failed to update badge: ${e.message}")
        }
    }

    /**
     * Manually disconnect
     */
    fun disconnect() {
        status = ConnectionStatus.Offline
        webSocketClient?.stop()
        webSocketClient = null
        debugWebSocketClient?.stop()
        debugWebSocketClient = null
        repository = null
        services = null
    }
}

/**
 * Composable wrapper for connection management
 */
@Composable
fun rememberConnectionManager(serverBaseUrl: String): ConnectionManager {
    val manager = remember { ConnectionManager(serverBaseUrl) }

    LaunchedEffect(serverBaseUrl) {
        manager.connect()
    }

    return manager
}
