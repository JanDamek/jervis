package com.jervis.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
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

            services = NetworkModule.createServicesFromUrl(serverBaseUrl, httpClient)
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

    // Launch main app
    App(
        repository = repository,
        defaultClientId = defaultClientId,
        defaultProjectId = defaultProjectId,
    )
}
