package com.jervis.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.jervis.repository.JervisRepository
import com.jervis.ui.navigation.AppNavigator
import com.jervis.ui.navigation.Screen

/**
 * Root Compose Application
 * Entry point for Desktop, Android and iOS
 */
@Composable
fun App(
    repository: JervisRepository,
    defaultClientId: String? = null,
    defaultProjectId: String? = null,
    modifier: Modifier = Modifier,
    navigator: AppNavigator? = null,
    onOpenDebugWindow: (() -> Unit)? = null,
) {
    val viewModel = remember { MainViewModel(repository, defaultClientId, defaultProjectId) }
    val snackbarHostState = remember { SnackbarHostState() }
    val appNavigator = navigator ?: remember { AppNavigator() }

    // Observe error messages and show snackbar
    val errorMessage by viewModel.errorMessage.collectAsState()
    LaunchedEffect(errorMessage) {
        errorMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    // Cleanup on dispose
    DisposableEffect(Unit) {
        onDispose {
            viewModel.onDispose()
        }
    }

    MaterialTheme {
        val clients by viewModel.clients.collectAsState()
        val projects by viewModel.projects.collectAsState()
        val selectedClientId by viewModel.selectedClientId.collectAsState()
        val selectedProjectId by viewModel.selectedProjectId.collectAsState()
        val chatMessages by viewModel.chatMessages.collectAsState()
        val inputText by viewModel.inputText.collectAsState()
        val isLoading by viewModel.isLoading.collectAsState()
        val currentScreen by appNavigator.currentScreen.collectAsState()

        when (val screen = currentScreen) {
            Screen.Main -> MainScreen(
                clients = clients,
                projects = projects,
                selectedClientId = selectedClientId,
                selectedProjectId = selectedProjectId,
                chatMessages = chatMessages,
                inputText = inputText,
                isLoading = isLoading,
                onClientSelected = viewModel::selectClient,
                onProjectSelected = viewModel::selectProject,
                onInputChanged = viewModel::updateInputText,
                onSendClick = viewModel::sendMessage,
                onNavigate = { screen ->
                    // Desktop has separate debug window, mobile uses navigation
                    if (screen == Screen.DebugConsole && onOpenDebugWindow != null) {
                        onOpenDebugWindow()
                    } else {
                        appNavigator.navigateTo(screen)
                    }
                },
                modifier = modifier,
            )
            Screen.Settings -> SettingsScreen(
                repository = repository,
                onBack = { appNavigator.navigateTo(Screen.Main) }
            )
            Screen.UserTasks -> UserTasksScreen(
                repository = repository,
                onBack = { appNavigator.navigateTo(Screen.Main) }
            )
            Screen.PendingTasks -> PendingTasksScreen(
                repository = repository,
                onBack = { appNavigator.navigateTo(Screen.Main) }
            )
            Screen.ErrorLogs -> ErrorLogsScreen(
                repository = repository,
                onBack = { appNavigator.navigateTo(Screen.Main) }
            )
            Screen.IndexingStatus -> IndexingStatusScreen(
                repository = repository,
                onBack = { appNavigator.navigateTo(Screen.Main) },
                onOpenDetail = { toolKey -> appNavigator.navigateTo(Screen.IndexingToolDetail(toolKey)) }
            )
            is Screen.IndexingToolDetail -> IndexingStatusDetailScreen(
                repository = repository,
                toolKey = screen.toolKey,
                onBack = { appNavigator.navigateTo(Screen.IndexingStatus) }
            )
            Screen.RagSearch -> RagSearchScreen(
                repository = repository,
                onBack = { appNavigator.navigateTo(Screen.Main) }
            )
            Screen.Scheduler -> SchedulerScreen(
                repository = repository,
                onBack = { appNavigator.navigateTo(Screen.Main) }
            )
            // Connections screen removed – connections are managed within Settings under Client/Project edit
            Screen.DebugConsole -> {
                val provider = LocalDebugEventsProvider.current
                requireNotNull(provider) { "DebugEventsProvider is not available. Provide it via LocalDebugEventsProvider at the app root." }
                DebugWindow(
                    eventsProvider = provider,
                    onBack = { appNavigator.navigateTo(Screen.Main) }
                )
            }
        }

        SnackbarHost(hostState = snackbarHostState)
    }
}
