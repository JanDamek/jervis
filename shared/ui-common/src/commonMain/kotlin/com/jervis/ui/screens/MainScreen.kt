package com.jervis.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.jervis.ui.MainViewModel
import com.jervis.ui.environment.EnvironmentPanel
import com.jervis.ui.MainScreenView as MainScreenViewInternal

@Composable
fun MainScreen(
    viewModel: MainViewModel,
    onNavigate: (com.jervis.ui.navigation.Screen) -> Unit = {},
) {
    val clients by viewModel.clients.collectAsState()
    val projects by viewModel.projects.collectAsState()
    val projectGroups by viewModel.projectGroups.collectAsState()
    val selectedClientId by viewModel.selectedClientId.collectAsState()
    val selectedProjectId by viewModel.selectedProjectId.collectAsState()
    val selectedGroupId by viewModel.selectedGroupId.collectAsState()
    val chatMessages by viewModel.chatMessages.collectAsState()
    val inputText by viewModel.inputText.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    val isInitialLoading by viewModel.isInitialLoading.collectAsState()
    val queueSize by viewModel.queueSize.collectAsState()
    val runningProjectId by viewModel.runningProjectId.collectAsState()
    val runningProjectName by viewModel.runningProjectName.collectAsState()
    val runningTaskPreview by viewModel.runningTaskPreview.collectAsState()
    val runningTaskType by viewModel.runningTaskType.collectAsState()
    val hasMore by viewModel.hasMore.collectAsState()
    val isLoadingMore by viewModel.isLoadingMore.collectAsState()
    val compressionBoundaries by viewModel.compressionBoundaries.collectAsState()
    val attachments by viewModel.attachments.collectAsState()
    val pendingMessageInfo by viewModel.pendingMessageInfo.collectAsState()
    val workspaceInfo by viewModel.workspaceInfo.collectAsState()
    val orchestratorHealthy by viewModel.orchestratorHealthy.collectAsState()

    // Environment panel state
    val environments by viewModel.environments.collectAsState()
    val resolvedEnvId by viewModel.resolvedEnvId.collectAsState()
    val environmentStatuses by viewModel.environmentStatuses.collectAsState()
    val environmentPanelVisible by viewModel.environmentPanelVisible.collectAsState()
    val environmentPanelWidthFraction by viewModel.environmentPanelWidthFraction.collectAsState()
    val expandedEnvIds by viewModel.expandedEnvIds.collectAsState()
    val expandedComponentIds by viewModel.expandedComponentIds.collectAsState()
    val environmentLoading by viewModel.environmentLoading.collectAsState()
    val environmentError by viewModel.environmentError.collectAsState()

    MainScreenViewInternal(
        clients = clients,
        projects = projects,
        projectGroups = projectGroups,
        selectedClientId = selectedClientId,
        selectedProjectId = selectedProjectId,
        selectedGroupId = selectedGroupId,
        chatMessages = chatMessages,
        inputText = inputText,
        isLoading = isLoading || isInitialLoading,
        queueSize = queueSize,
        runningProjectId = runningProjectId,
        runningProjectName = runningProjectName,
        runningTaskPreview = runningTaskPreview,
        runningTaskType = runningTaskType,
        hasMore = hasMore,
        isLoadingMore = isLoadingMore,
        compressionBoundaries = compressionBoundaries,
        attachments = attachments,
        onClientSelected = viewModel::selectClient,
        onProjectSelected = { id -> viewModel.selectProject(id ?: "") },
        onGroupSelected = viewModel::selectGroup,
        onInputChanged = viewModel::updateInputText,
        onSendClick = viewModel::sendMessage,
        onNavigate = onNavigate,
        onAgentStatusClick = { onNavigate(com.jervis.ui.navigation.Screen.AgentWorkload) },
        connectionState = connectionState,
        onReconnect = viewModel::manualReconnect,
        onEditMessage = viewModel::editMessage,
        onLoadMore = viewModel::loadMoreHistory,
        onAttachFile = viewModel::attachFile,
        onRemoveAttachment = viewModel::removeAttachment,
        pendingMessageInfo = pendingMessageInfo,
        onRetryPending = viewModel::retrySendMessage,
        onCancelPending = viewModel::cancelRetry,
        workspaceInfo = workspaceInfo,
        onRetryWorkspace = viewModel::retryWorkspace,
        orchestratorHealthy = orchestratorHealthy,
        hasEnvironment = environments.isNotEmpty(),
        environmentPanelVisible = environmentPanelVisible,
        onToggleEnvironmentPanel = viewModel::toggleEnvironmentPanel,
        panelWidthFraction = environmentPanelWidthFraction,
        onPanelWidthChange = viewModel::updatePanelWidthFraction,
        environmentPanelContent = { isCompact ->
            EnvironmentPanel(
                environments = environments,
                statuses = environmentStatuses,
                resolvedEnvId = resolvedEnvId,
                isLoading = environmentLoading,
                error = environmentError,
                isCompact = isCompact,
                expandedEnvIds = expandedEnvIds,
                expandedComponentIds = expandedComponentIds,
                onToggleEnv = viewModel::toggleEnvExpanded,
                onToggleComponent = viewModel::toggleComponentExpanded,
                onClose = viewModel::closeEnvironmentPanel,
                onRefresh = viewModel::refreshEnvironments,
            )
        },
    )
}
