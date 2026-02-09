package com.jervis.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jervis.di.NetworkModule
import com.jervis.repository.JervisRepository
import io.ktor.client.request.get
import kotlinx.coroutines.launch

/**
 * Main Jervis Application Composable
 * Shared across Desktop, Android, iOS
 *
 * @param serverBaseUrl Base URL of the Jervis server (e.g., "https://jervis.damek-soft.eu")
 * @param defaultClientId Optional default client ID
 * @param defaultProjectId Optional default project ID
 */
@Composable
fun JervisApp(
    serverBaseUrl: String,
    defaultClientId: String? = null,
    defaultProjectId: String? = null,
) {
    // Initialize services with refresh counter to force recreation
    var servicesState by remember { mutableStateOf<Pair<String, NetworkModule.Services>?>(null) }
    var refreshTrigger by remember { mutableStateOf(0) }

    LaunchedEffect(serverBaseUrl, refreshTrigger) {
        try {
            println("=== Jervis App: Initializing connection to $serverBaseUrl ===")
            val httpClient = NetworkModule.createHttpClient()
            println("=== Jervis App: HTTP client created ===")

            // Test basic HTTPS connection first
            try {
                println("=== Jervis App: Testing HTTPS connection to $serverBaseUrl ===")
                val testResponse = httpClient.get("$serverBaseUrl/")
                println("=== Jervis App: HTTPS test successful, status: ${testResponse.status} ===")
            } catch (e: Exception) {
                println("=== Jervis App: HTTPS test FAILED: ${e::class.simpleName}: ${e.message} ===")
                e.printStackTrace()
                throw e
            }

            val services = NetworkModule.createServicesFromUrl(serverBaseUrl, httpClient)
            servicesState = serverBaseUrl to services
            println("=== Jervis App: Services initialized successfully ===")
        } catch (e: Exception) {
            println("=== Jervis App ERROR: ${e::class.simpleName}: ${e.message} ===")
            e.printStackTrace()
            // Fallback or show error
        }
    }

    // Create repository only when services are ready
    val currentServices = servicesState?.second
    if (currentServices == null) {
        // Show loading screen while connecting
        MaterialTheme {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background,
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        CircularProgressIndicator()
                        Text("Connecting to server...")
                        Text(
                            text = serverBaseUrl,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        return
    }

    val repository =
        remember(currentServices) {
            JervisRepository(
                clients = currentServices.clientService,
                projects = currentServices.projectService,
                projectGroups = currentServices.projectGroupService,
                environments = currentServices.environmentService,
                userTasks = currentServices.userTaskService,
                ragSearch = currentServices.ragSearchService,
                scheduledTasks = currentServices.taskSchedulingService,
                agentOrchestrator = currentServices.agentOrchestratorService,
                errorLogs = currentServices.errorLogService,
                pendingTasks = currentServices.pendingTaskService,
                connections = currentServices.connectionService,
                notifications = currentServices.notificationService,
                bugTrackerSetup = currentServices.bugTrackerSetupService,
                codingAgents = currentServices.codingAgentSettingsService,
                meetings = currentServices.meetingService,
                transcriptCorrections = currentServices.transcriptCorrectionService,
                deviceTokens = currentServices.deviceTokenService,
            )
        }

    val reconnectScope = rememberCoroutineScope()

    // Launch main app
    App(
        repository = repository,
        defaultClientId = defaultClientId,
        defaultProjectId = defaultProjectId,
        onRefreshConnection = {
            // Force complete recreation of RPC client and repository
            reconnectScope.launch {
                println("=== JervisApp: Full reconnect triggered, clearing services ===")
                servicesState = null
                refreshTrigger++
            }
        },
    )
}
