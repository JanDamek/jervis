package com.jervis.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.jervis.di.NetworkModule
import com.jervis.di.createJervisServices
import com.jervis.repository.JervisRepository
import kotlinx.rpc.krpc.ktor.client.KtorRpcClient

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
            val httpClient = NetworkModule.createHttpClient()
            val rpcClient = NetworkModule.createRpcClient(serverBaseUrl, httpClient)
            services = NetworkModule.createServices(rpcClient = rpcClient)
        } catch (e: Exception) {
            println("Error connecting to server: ${e.message}")
            // Fallback or show error
        }
    }

    // Create repository only when services are ready
    val currentServices = services
    if (currentServices == null) {
        // Show loading or splash screen while connecting
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
