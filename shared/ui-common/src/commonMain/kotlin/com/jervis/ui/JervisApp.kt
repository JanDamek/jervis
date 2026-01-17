package com.jervis.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jervis.di.NetworkModule
import com.jervis.repository.JervisRepository
import io.ktor.client.request.get

/**
 * Main Jervis Application Composable
 * Shared across Desktop, Android, iOS
 *
 * @param serverBaseUrl Base URL of the Jervis server (e.g., "http://localhost:5500")
 * @param defaultClientId Optional default client ID
 * @param defaultProjectId Optional default project ID
 * @param debugEventsProvider Optional DebugEventsProvider for Debug Console (mobile provides lifecycle-aware version)
 */
@Composable
fun JervisApp(
    serverBaseUrl: String,
    defaultClientId: String? = null,
    defaultProjectId: String? = null,
    debugEventsProvider: DebugEventsProvider? = null,
) {
    // Initialize services
    var services by remember { mutableStateOf<NetworkModule.Services?>(null) }

    LaunchedEffect(serverBaseUrl) {
        try {
            println("=== Jervis App: Initializing connection to $serverBaseUrl ===")
            val httpClient = NetworkModule.createHttpClient()
            println("=== Jervis App: HTTP client created ===")

            // Test basic HTTPS connection first
            try {
                println("=== Jervis App: Testing HTTPS connection to $serverBaseUrl ===")
                val testResponse = httpClient.get("$serverBaseUrl/actuator/health")
                println("=== Jervis App: HTTPS test successful, status: ${testResponse.status} ===")
            } catch (e: Exception) {
                println("=== Jervis App: HTTPS test FAILED: ${e::class.simpleName}: ${e.message} ===")
                e.printStackTrace()
                throw e
            }

            val rpcClient = NetworkModule.createRpcClient(serverBaseUrl, httpClient)
            println("=== Jervis App: RPC client created ===")
            services = NetworkModule.createServices(rpcClient)
            println("=== Jervis App: Services initialized successfully ===")
        } catch (e: Exception) {
            println("=== Jervis App ERROR: ${e::class.simpleName}: ${e.message} ===")
            e.printStackTrace()
            // Fallback or show error
        }
    }

    // Create repository only when services are ready
    val currentServices = services
    if (currentServices == null) {
        // Show loading screen while connecting
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                CircularProgressIndicator()
                Text("Connecting to server...")
                Text(
                    text = serverBaseUrl,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        return
    }

    val repository =
        remember(currentServices) {
            JervisRepository(
                clientService = currentServices.clientService,
                projectService = currentServices.projectService,
                userTaskService = currentServices.userTaskService,
                ragSearchService = currentServices.ragSearchService,
                taskSchedulingService = currentServices.taskSchedulingService,
                agentOrchestratorService = currentServices.agentOrchestratorService,
                errorLogService = currentServices.errorLogService,
                gitConfigurationService = currentServices.gitConfigurationService,
                pendingTaskService = currentServices.pendingTaskService,
                connectionService = currentServices.connectionService,
            )
        }

    // Provide debug events provider via CompositionLocal (if provided by platform)
    CompositionLocalProvider(LocalDebugEventsProvider provides debugEventsProvider) {
        // Launch main app
        App(
            repository = repository,
            defaultClientId = defaultClientId,
            defaultProjectId = defaultProjectId,
        )
    }
}
