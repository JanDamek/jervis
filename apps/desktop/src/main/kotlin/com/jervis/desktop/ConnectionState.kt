package com.jervis.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.jervis.di.NetworkModule
import com.jervis.repository.JervisRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Connection state for the desktop application
 * Handles server connectivity and automatic reconnection
 */
sealed class ConnectionStatus {
    data object Connecting : ConnectionStatus()

    data object Connected : ConnectionStatus()

    data class Disconnected(
        val error: String,
    ) : ConnectionStatus()

    data object Offline : ConnectionStatus()
}

/**
 * Manages connection to Jervis server with automatic retry
 */
class ConnectionManager(
    private val serverBaseUrl: String,
) {
    var status by mutableStateOf<ConnectionStatus>(ConnectionStatus.Connecting)
        private set

    var repository by mutableStateOf<JervisRepository?>(null)
        private set

    var taskBadgeCount by mutableStateOf(0)
        private set

    var errorNotifications by mutableStateOf<List<com.jervis.dto.events.JervisEvent.ErrorNotification>>(emptyList())
        private set

    private var services: NetworkModule.Services? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var eventJob: kotlinx.coroutines.Job? = null

    /**
     * Check if the server is still reachable.
     * If not, it triggers reconnection logic.
     */
    fun checkConnectivity() {
        if (status is ConnectionStatus.Connected) {
            scope.launch {
                try {
                    repository?.clients?.getAllClients()
                } catch (e: Exception) {
                    println("Connectivity check failed: ${e.message}")
                    // Use NetworkModule reconnect instead of full re-initialization
                    tryReconnect()
                }
            }
        }
    }

    /**
     * Reconnect using NetworkModule's reconnection logic.
     * Falls back to full connect() if NetworkModule reconnect fails.
     */
    private fun tryReconnect() {
        scope.launch {
            try {
                status = ConnectionStatus.Connecting
                println("Attempting NetworkModule reconnect...")
                NetworkModule.reconnect()
                status = ConnectionStatus.Connected
                println("Reconnection successful via NetworkModule")
            } catch (e: Exception) {
                println("NetworkModule reconnect failed: ${e.message}, falling back to full connect()")
                connect() // Full re-initialization as fallback
            }
        }
    }

    /**
     * Initialize connection and start retry loop
     */
    suspend fun connect() {
        while (true) {
            try {
                status = ConnectionStatus.Connecting

                // Use NetworkModule.createServicesFromUrl() to ensure centralized RPC client management
                val httpClient = NetworkModule.createHttpClient()
                services = NetworkModule.createServicesFromUrl(serverBaseUrl, httpClient)

                // Create repository
                repository =
                    JervisRepository(
                        clients = services!!.clientService,
                        projects = services!!.projectService,
                        projectGroups = services!!.projectGroupService,
                        environments = services!!.environmentService,
                        userTasks = services!!.userTaskService,
                        ragSearch = services!!.ragSearchService,
                        scheduledTasks = services!!.taskSchedulingService,
                        agentOrchestrator = services!!.agentOrchestratorService,
                        errorLogs = services!!.errorLogService,
                        pendingTasks = services!!.pendingTaskService,
                        connections = services!!.connectionService,
                        notifications = services!!.notificationService,
                        bugTrackerSetup = services!!.bugTrackerSetupService,
                        codingAgents = services!!.codingAgentSettingsService,
                        whisperSettings = services!!.whisperSettingsService,
                        pollingIntervals = services!!.pollingIntervalService,
                        meetings = services!!.meetingService,
                        transcriptCorrections = services!!.transcriptCorrectionService,
                        deviceTokens = services!!.deviceTokenService,
                        indexingQueue = services!!.indexingQueueService,
                    )

                // Try a simple test call to verify connectivity
                try {
                    val clients = repository!!.clients.getAllClients()
                    status = ConnectionStatus.Connected

                    // Start event stream for the first client (Desktop usually manages one or all)
                    clients.firstOrNull()?.let { client ->
                        startEventStream(client.id)
                    }

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

    private suspend fun startEventStream(clientId: String) {
        eventJob?.cancel()
        val repo = repository ?: return

        eventJob =
            scope.launch {
                repo.notifications
                    .subscribeToEvents(clientId)
                    .collect { event ->
                        handleEvent(event)
                    }
            }
    }

    private suspend fun handleEvent(event: com.jervis.dto.events.JervisEvent) {
        when (event) {
            is com.jervis.dto.events.JervisEvent.UserTaskCreated -> {
                println("User task created: ${event.title} (approval=${event.isApproval}, action=${event.interruptAction})")
                updateTaskBadge()
                val notifTitle = if (event.isApproval) "Schválení vyžadováno" else "Nová úloha"
                MacOSUtils.showNotification(notifTitle, event.title)
            }

            is com.jervis.dto.events.JervisEvent.UserTaskCancelled -> {
                println("User task cancelled: ${event.title}")
                updateTaskBadge()
            }
            
            is com.jervis.dto.events.JervisEvent.PendingTaskCreated -> {
                // Ignore for badge
            }

            is com.jervis.dto.events.JervisEvent.ErrorNotification -> {
                println("Error: ${event.message}")
                errorNotifications = errorNotifications + event
                MacOSUtils.showNotification("Error", event.message)
            }

            is com.jervis.dto.events.JervisEvent.MeetingStateChanged -> { /* handled by MeetingViewModel */ }
            is com.jervis.dto.events.JervisEvent.MeetingTranscriptionProgress -> { /* handled by MeetingViewModel */ }
            is com.jervis.dto.events.JervisEvent.MeetingCorrectionProgress -> { /* handled by MeetingViewModel */ }
            is com.jervis.dto.events.JervisEvent.OrchestratorTaskProgress -> { /* handled by MainViewModel */ }
            is com.jervis.dto.events.JervisEvent.OrchestratorTaskStatusChange -> { /* handled by MainViewModel */ }
        }
    }

    /**
     * Update task badge count
     */
    private suspend fun updateTaskBadge() {
        try {
            val repo = repository ?: return
            val clients = repo.clients.getAllClients()
            var total = 0
            for (client in clients) {
                runCatching {
                    val countDto = repo.userTasks.activeCount(client.id)
                    total += countDto.activeCount
                }
            }
            taskBadgeCount = total
            MacOSUtils.setDockBadgeCount(total)
        } catch (e: Exception) {
            println("Failed to update badge: ${e.message}")
        }
    }

    /**
     * Force a full reconnect (new HttpClient + RPC services).
     * Called by MainViewModel when it detects a dead RPC connection.
     */
    fun forceReconnect() {
        scope.launch {
            println("=== ConnectionManager: Full reconnect triggered ===")
            connect()
        }
    }

    /**
     * Manually disconnect
     */
    fun disconnect() {
        status = ConnectionStatus.Offline
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
