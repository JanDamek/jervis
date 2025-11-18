package com.jervis.ui

import androidx.compose.runtime.*
import com.jervis.di.createJervisServices
import com.jervis.repository.JervisRepository

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
    val services = remember { createJervisServices(serverBaseUrl) }

    // Create repository
    val repository = remember {
        JervisRepository(
            clientService = services.clientService,
            projectService = services.projectService,
            userTaskService = services.userTaskService,
            ragSearchService = services.ragSearchService,
            taskSchedulingService = services.taskSchedulingService,
            agentOrchestratorService = services.agentOrchestratorService,
            errorLogService = services.errorLogService,
            integrationSettingsService = services.integrationSettingsService,
            gitConfigurationService = services.gitConfigurationService,
            atlassianSetupService = services.atlassianSetupService,
            emailAccountService = services.emailAccountService,
            indexingStatusService = services.indexingStatusService,
            pendingTaskService = services.pendingTaskService,
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
