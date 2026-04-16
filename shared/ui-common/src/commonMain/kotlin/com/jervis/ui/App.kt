package com.jervis.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.jervis.di.RpcConnectionManager
import com.jervis.di.JervisRepository
import com.jervis.ui.design.JervisTheme
import com.jervis.ui.navigation.AppNavigator
import com.jervis.ui.navigation.Screen
import com.jervis.ui.notification.UserTaskNotificationDialog
import com.jervis.ui.screens.*

/**
 * RPC connection generation counter — increments on each reconnect.
 * Use as LaunchedEffect key to reload data after server restart.
 */
val LocalRpcGeneration = compositionLocalOf { 0L }

/**
 * Root Compose Application
 * Entry point for Desktop, Android and iOS
 */
@Composable
fun App(
    repository: JervisRepository,
    connectionManager: RpcConnectionManager,
    defaultClientId: String? = null,
    defaultProjectId: String? = null,
    modifier: Modifier = Modifier,
    navigator: AppNavigator? = null,
    onOpenDebugWindow: (() -> Unit)? = null,
) {
    val viewModel = remember(repository) { MainViewModel(repository, connectionManager, defaultClientId, defaultProjectId) }
    val uploadService = remember(repository) {
        com.jervis.ui.meeting.RecordingUploadService(
            connectionManager = connectionManager,
            repository = repository,
        )
    }
    val meetingViewModel = remember(repository, uploadService) {
        com.jervis.ui.meeting.MeetingViewModel(
            connectionManager = connectionManager,
            repository = repository,
            uploadService = uploadService,
        )
    }
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

    // Cleanup on dispose (keyed on viewModel so old one gets disposed on reconnect)
    DisposableEffect(viewModel) {
        onDispose {
            viewModel.onDispose()
        }
    }

    val rpcGeneration by connectionManager.generation.collectAsState()

    JervisTheme {
      CompositionLocalProvider(LocalRpcGeneration provides rpcGeneration) {
      Surface(
          modifier = Modifier.fillMaxSize().safeDrawingPadding(),
          color = MaterialTheme.colorScheme.background,
      ) {
        val clients by viewModel.clients.collectAsState()
        val projects by viewModel.projects.collectAsState()
        val projectGroups by viewModel.projectGroups.collectAsState()
        val selectedClientId by viewModel.selectedClientId.collectAsState()
        val selectedProjectId by viewModel.selectedProjectId.collectAsState()
        val selectedGroupId by viewModel.selectedGroupId.collectAsState()
        val currentScreen by appNavigator.currentScreen.collectAsState()
        val canGoBack by appNavigator.canGoBack.collectAsState()
        val connectionState by viewModel.connection.state.collectAsState()
        val connectionStatusDetail by viewModel.connection.statusDetail.collectAsState()
        val isInitialLoading by viewModel.connection.isInitialLoading.collectAsState()

        // Global recording state
        val isRecordingGlobal by meetingViewModel.isRecording.collectAsState()
        val recordingDurationGlobal by meetingViewModel.recordingDuration.collectAsState()
        val uploadStateGlobal by meetingViewModel.uploadState.collectAsState()

        // Meeting helper state
        val helperMessages by meetingViewModel.helperMessages.collectAsState()
        val helperConnected by meetingViewModel.helperConnected.collectAsState()
        val liveAssistActive by meetingViewModel.liveAssistActive.collectAsState()
        val liveHints by meetingViewModel.liveHints.collectAsState()

        // Environment
        val environments by viewModel.environment.environments.collectAsState()

        // Paměťový graf
        val activeMemoryGraph by viewModel.chat.activeThinkingGraph.collectAsState()

        // User task count for badge
        val userTaskCount by viewModel.notification.userTaskCount.collectAsState()

        // Auto-navigate to Assistant screen when the live helper session
        // activates. Assistant is effectively the home page while a meeting
        // is running; user can toggle back to chat via the top-bar icon.
        LaunchedEffect(helperConnected) {
            if (helperConnected && currentScreen == Screen.Main) {
                appNavigator.navigateTo(Screen.Assistant)
            }
        }

        Column(modifier = Modifier.fillMaxSize()) {
        // Persistent top bar — always visible
        PersistentTopBar(
            canGoBack = canGoBack,
            onBack = { appNavigator.goBack() },
            onNavigate = { screen -> appNavigator.navigateTo(screen) },
            clients = clients,
            projects = projects,
            projectGroups = projectGroups,
            selectedClientId = selectedClientId,
            selectedProjectId = selectedProjectId,
            selectedGroupId = selectedGroupId,
            onClientSelected = viewModel::selectClient,
            onProjectSelected = { id -> viewModel.selectProject(id ?: "") },
            onGroupSelected = viewModel::selectGroup,
            connectionState = connectionState,
            connectionStatusDetail = connectionStatusDetail,
            onReconnect = viewModel.connection::manualReconnect,
            isRecording = isRecordingGlobal,
            recordingDuration = recordingDurationGlobal,
            onNavigateToMeetings = { appNavigator.navigateTo(Screen.Meetings) },
            onQuickRecord = { meetingViewModel.startQuickRecording() },
            onStopRecording = { meetingViewModel.stopRecording() },
            hasMemoryGraph = activeMemoryGraph != null,
            onToggleThinkingGraphPanel = viewModel.chat::toggleThinkingGraphPanel,
            hasEnvironment = environments.isNotEmpty(),
            onToggleEnvironmentPanel = viewModel.environment::togglePanel,
            userTaskCount = userTaskCount,
            assistantActive = helperConnected || helperMessages.isNotEmpty() || isRecordingGlobal,
            isOnAssistant = currentScreen == Screen.Assistant,
            onOpenAssistant = {
                if (currentScreen == Screen.Assistant) {
                    appNavigator.navigateTo(Screen.Main)
                } else {
                    appNavigator.navigateTo(Screen.Assistant)
                }
            },
        )

        // Global recording bar — full controls when recording
        if (isRecordingGlobal) {
            com.jervis.ui.util.KeepScreenOn()
            com.jervis.ui.meeting.RecordingBar(
                durationSeconds = recordingDurationGlobal,
                uploadState = uploadStateGlobal,
                onStop = { meetingViewModel.stopRecording() },
                onNavigateToMeetings = { appNavigator.navigateTo(Screen.Meetings) },
                isOnMeetingsScreen = currentScreen == Screen.Meetings,
                liveAssistActive = liveAssistActive,
                onToggleLiveAssist = { meetingViewModel.toggleLiveAssist() },
            )

            // Meeting helper panel — shown below recording bar when helper messages arrive.
            // Suppressed on Assistant screen to avoid duplicate rendering of the same content.
            if ((helperMessages.isNotEmpty() || helperConnected) && currentScreen != Screen.Assistant) {
                com.jervis.ui.meeting.MeetingHelperView(
                    messages = helperMessages,
                    meetingTitle = null,
                    isConnected = helperConnected,
                    onDisconnect = { meetingViewModel.disconnectHelper() },
                    modifier = androidx.compose.ui.Modifier.heightIn(max = 200.dp),
                )
            }

            // Live assist hints — single bubble with accumulated KB hints.
            // Suppressed on Assistant screen (same content rendered full-screen there).
            if (liveAssistActive && liveHints.isNotEmpty() && currentScreen != Screen.Assistant) {
                com.jervis.ui.meeting.LiveHintsBubble(
                    hints = liveHints,
                    modifier = androidx.compose.ui.Modifier.heightIn(max = 200.dp),
                )
            }
        }

        when (currentScreen) {
            Screen.Main -> {
                MainScreen(
                    viewModel = viewModel,
                    repository = repository,
                    onOpenEnvironmentManager = { envId ->
                        viewModel.environment.closePanel()
                        // Environment panel stays as right sidebar, no separate screen
                        viewModel.environment.togglePanel()
                    },
                )
            }

            Screen.Settings -> {
                com.jervis.ui.screens.settings.SettingsScreen(
                    repository = repository,
                    onBack = { appNavigator.goBack() },
                    onNavigate = { appNavigator.navigateTo(it) },
                    onDataChanged = { viewModel.refreshProjects() },
                )
            }

            Screen.Meetings -> {
                com.jervis.ui.meeting.MeetingsScreen(
                    viewModel = meetingViewModel,
                    clients = clients,
                    selectedClientId = selectedClientId,
                    selectedProjectId = selectedProjectId,
                    onBack = { appNavigator.goBack() },
                    uploadService = uploadService,
                )
            }

            Screen.Calendar -> {
                // TODO: Calendar grid view (step 4)
                SchedulerScreen(
                    repository = repository,
                    onBack = { appNavigator.goBack() },
                )
            }

            Screen.Assistant -> {
                com.jervis.ui.meeting.AssistantScreen(
                    messages = helperMessages,
                    isConnected = helperConnected,
                )
            }
        }
        } // Column

        SnackbarHost(hostState = snackbarHostState)

        // User task notification dialog — shown for all user tasks (approval + clarification).
        // Suppressed while the Assistant screen is active so it doesn't overlay the live
        // hints during a meeting. The event stays in the queue (userTaskDialogEvent) and
        // the top-bar badge shows the count — user sees it after leaving Assistant.
        val userTaskEvent by viewModel.notification.userTaskDialogEvent.collectAsState()
        if (currentScreen != Screen.Assistant) {
        userTaskEvent?.let { event ->
            UserTaskNotificationDialog(
                event = event,
                onApprove = { taskId -> viewModel.notification.approveTask(taskId, viewModel::reportError) },
                onDeny = { taskId, reason -> viewModel.notification.denyTask(taskId, reason, viewModel::reportError) },
                onReply = { taskId, reply ->
                    viewModel.notification.replyToTask(taskId, reply, viewModel::reportError)
                    // Navigate to the relevant project if projectId is available
                    event.projectId?.let { projectId ->
                        viewModel.selectClient(event.clientId)
                        viewModel.selectProject(projectId)
                        appNavigator.navigateAndClearHistory(Screen.Main)
                    }
                },
                onDismiss = { viewModel.notification.dismissUserTaskDialog() },
                onRetry = { taskId -> viewModel.notification.retryTask(taskId, viewModel::reportError) },
                onDiscard = { taskId -> viewModel.notification.discardTask(taskId, viewModel::reportError) },
            )
        }
        }

      }
      } // CompositionLocalProvider
    }
}
