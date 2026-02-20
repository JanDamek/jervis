package com.jervis.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.jervis.di.OfflineException
import com.jervis.di.RpcConnectionManager
import com.jervis.di.RpcConnectionState
import com.jervis.repository.JervisRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
 * Manages connection to Jervis server.
 * Delegates to [RpcConnectionManager] for actual connection lifecycle.
 */
class ConnectionManager(
    private val serverBaseUrl: String,
) {
    var status by mutableStateOf<ConnectionStatus>(ConnectionStatus.Connecting)
        private set

    // Eager non-nullable repository — services lambda is only called on actual RPC invocations,
    // not at construction time. If offline, throws OfflineException which callers handle.
    val repository: JervisRepository = JervisRepository {
        rpcConnectionManager.getServices() ?: throw OfflineException()
    }

    var taskBadgeCount by mutableStateOf(0)
        private set

    var errorNotifications by mutableStateOf<List<com.jervis.dto.events.JervisEvent.ErrorNotification>>(emptyList())
        private set

    val rpcConnectionManager = RpcConnectionManager(serverBaseUrl)

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var eventJob: kotlinx.coroutines.Job? = null

    /**
     * Initialize connection via RpcConnectionManager and observe its state.
     */
    suspend fun connect() {
        // Observe RpcConnectionManager state
        scope.launch {
            rpcConnectionManager.state.collect { rpcState ->
                when (rpcState) {
                    is RpcConnectionState.Connected -> {
                        status = ConnectionStatus.Connected

                        // Start event stream for tray badge + notifications
                        try {
                            val clients = repository.clients.getAllClients()
                            clients.firstOrNull()?.let { client ->
                                startEventStream(client.id)
                            }
                            updateTaskBadge()
                        } catch (e: Exception) {
                            println("Desktop: Failed to start event stream after connect: ${e.message}")
                        }
                    }
                    is RpcConnectionState.Connecting -> {
                        status = ConnectionStatus.Connecting
                    }
                    is RpcConnectionState.Disconnected -> {
                        status = ConnectionStatus.Disconnected("Connection lost")
                    }
                }
            }
        }

        // Start connection (blocks until first successful connect, then monitors)
        rpcConnectionManager.connect()
    }

    private fun startEventStream(clientId: String) {
        eventJob?.cancel()
        eventJob = scope.launch {
            rpcConnectionManager.resilientFlow { services ->
                services.notificationService.subscribeToEvents(clientId)
            }.collect { event ->
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
            is com.jervis.dto.events.JervisEvent.QualificationProgress -> { /* handled by MainViewModel */ }
        }
    }

    /**
     * Update task badge count
     */
    private suspend fun updateTaskBadge() {
        try {
            val clients = repository.clients.getAllClients()
            var total = 0
            for (client in clients) {
                runCatching {
                    val countDto = repository.userTasks.activeCount(client.id)
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
     * Manually disconnect
     */
    fun disconnect() {
        status = ConnectionStatus.Offline
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
