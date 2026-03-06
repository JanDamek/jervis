package com.jervis.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.jervis.dto.ui.ChatMessage
import com.jervis.dto.graph.TaskGraphDto
import com.jervis.ui.MainViewModel
import com.jervis.ui.environment.EnvironmentPanel
import com.jervis.ui.MainScreenView as MainScreenViewInternal

@Composable
fun MainScreen(
    viewModel: MainViewModel,
    onOpenEnvironmentManager: (String) -> Unit = {},
) {
    val isOffline by viewModel.connection.isOffline.collectAsState()
    val selectedClientId by viewModel.selectedClientId.collectAsState()
    val selectedProjectId by viewModel.selectedProjectId.collectAsState()
    val chatMessages by viewModel.chat.chatMessages.collectAsState()
    val showChat by viewModel.chat.showChat.collectAsState()
    val showTasks by viewModel.chat.showTasks.collectAsState()
    val showNeedReaction by viewModel.chat.showNeedReaction.collectAsState()
    val backgroundMessageCount by viewModel.chat.backgroundMessageCount.collectAsState()
    val userTaskCount by viewModel.chat.userTaskCount.collectAsState()

    // Client-side filtering — instant toggle, no server reload
    val filteredMessages = remember(chatMessages, showChat, showTasks, showNeedReaction) {
        chatMessages.filter { msg ->
            when (msg.messageType) {
                ChatMessage.MessageType.BACKGROUND_RESULT -> when {
                    showTasks -> true   // "Tasky" shows ALL backgrounds
                    showNeedReaction -> msg.metadata["needsReaction"] == "true"
                    else -> false       // both off → hide backgrounds
                }
                else -> showChat
            }
        }
    }
    val inputText by viewModel.chat.inputText.collectAsState()
    val isChatLoading by viewModel.chat.isLoading.collectAsState()
    val isInitialLoading by viewModel.connection.isInitialLoading.collectAsState()
    val queueSize by viewModel.queue.queueSize.collectAsState()
    val hasMore by viewModel.chat.hasMore.collectAsState()
    val isLoadingMore by viewModel.chat.isLoadingMore.collectAsState()
    val compressionBoundaries by viewModel.chat.compressionBoundaries.collectAsState()
    val attachments by viewModel.chat.attachments.collectAsState()
    val pendingMessageInfo by viewModel.chat.pendingMessageInfo.collectAsState()
    val approvalRequest by viewModel.chat.approvalRequest.collectAsState()
    val workspaceInfo by viewModel.workspaceInfo.collectAsState()
    val orchestratorHealthy by viewModel.queue.orchestratorHealthy.collectAsState()
    val orchestratorProgress by viewModel.queue.orchestratorProgress.collectAsState()
    val taskGraphs by viewModel.chat.taskGraphs.collectAsState()
    val activeThinkingMap by viewModel.chat.activeThinkingMap.collectAsState()
    val thinkingMaps by viewModel.chat.thinkingMaps.collectAsState()
    val thinkingMapPanelVisible by viewModel.chat.thinkingMapPanelVisible.collectAsState()
    val thinkingMapPanelWidthFraction by viewModel.chat.thinkingMapPanelWidthFraction.collectAsState()

    // Environment panel state (delegated to EnvironmentViewModel)
    val environments by viewModel.environment.environments.collectAsState()
    val resolvedEnvId by viewModel.environment.resolvedEnvId.collectAsState()
    val environmentStatuses by viewModel.environment.environmentStatuses.collectAsState()
    val environmentPanelVisible by viewModel.environment.panelVisible.collectAsState()
    val environmentPanelWidthFraction by viewModel.environment.panelWidthFraction.collectAsState()
    val expandedEnvIds by viewModel.environment.expandedEnvIds.collectAsState()
    val expandedComponentIds by viewModel.environment.expandedComponentIds.collectAsState()
    val environmentLoading by viewModel.environment.loading.collectAsState()
    val environmentError by viewModel.environment.error.collectAsState()
    val selectedEnvironmentId by viewModel.environment.selectedEnvironmentId.collectAsState()

    // Compute active environment summary reactively from collected state
    val activeEnvId = resolvedEnvId ?: selectedEnvironmentId
    val activeEnvSummary = remember(activeEnvId, environments, environmentStatuses) {
        if (activeEnvId == null) return@remember null
        val env = environments.find { it.id == activeEnvId } ?: return@remember null
        val status = environmentStatuses[activeEnvId]
        val state = status?.state ?: env.state
        "${env.name} (${env.namespace}) — $state"
    }

    MainScreenViewInternal(
        selectedClientId = selectedClientId,
        selectedProjectId = selectedProjectId,
        chatMessages = filteredMessages,
        inputText = inputText,
        isLoading = isChatLoading || isInitialLoading,
        isOffline = isOffline,
        queueSize = queueSize,
        hasMore = hasMore,
        isLoadingMore = isLoadingMore,
        compressionBoundaries = compressionBoundaries,
        attachments = attachments,
        onInputChanged = viewModel.chat::updateInputText,
        onSendClick = viewModel.chat::sendMessage,
        onEditMessage = viewModel.chat::editMessage,
        onReplyToTask = viewModel.chat::replyToTask,
        onSendReply = viewModel.chat::sendReplyToTask,
        onLoadMore = viewModel.chat::loadMoreHistory,
        onAttachFile = viewModel.chat::attachFile,
        onRemoveAttachment = viewModel.chat::removeAttachment,
        pendingMessageInfo = pendingMessageInfo,
        onRetryPending = viewModel.chat::retrySendMessage,
        onCancelPending = viewModel.chat::cancelRetry,
        approvalRequest = approvalRequest,
        onApproveOnce = { viewModel.chat.approveChatAction(always = false) },
        onApproveAlways = { viewModel.chat.approveChatAction(always = true) },
        onDenyAction = viewModel.chat::denyChatAction,
        workspaceInfo = workspaceInfo,
        onRetryWorkspace = viewModel::retryWorkspace,
        orchestratorHealthy = orchestratorHealthy,
        orchestratorProgress = orchestratorProgress,
        taskGraphs = taskGraphs,
        onLoadTaskGraph = viewModel.chat::loadTaskGraph,
        showChat = showChat,
        onToggleChat = viewModel.chat::toggleChat,
        showTasks = showTasks,
        onToggleTasks = viewModel.chat::toggleTasks,
        showNeedReaction = showNeedReaction,
        onToggleNeedReaction = viewModel.chat::toggleNeedReaction,
        backgroundMessageCount = backgroundMessageCount,
        userTaskCount = userTaskCount,
        activeThinkingMap = activeThinkingMap,
        thinkingMapPanelVisible = thinkingMapPanelVisible,
        thinkingMapPanelWidthFraction = thinkingMapPanelWidthFraction,
        onThinkingMapPanelWidthChange = viewModel.chat::updateThinkingMapPanelWidthFraction,
        thinkingMapPanelContent = { isCompact ->
            com.jervis.ui.chat.ThinkingMapPanel(
                activeMap = activeThinkingMap,
                allMaps = thinkingMaps,
                onSelectMap = viewModel.chat::selectThinkingMap,
                isCompact = isCompact,
                onClose = viewModel.chat::closeThinkingMapPanel,
            )
        },
        hasEnvironment = environments.isNotEmpty(),
        environmentPanelVisible = environmentPanelVisible,
        onToggleEnvironmentPanel = viewModel.environment::togglePanel,
        panelWidthFraction = environmentPanelWidthFraction,
        onPanelWidthChange = viewModel.environment::updatePanelWidthFraction,
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
                onToggleEnv = viewModel.environment::toggleEnvExpanded,
                onToggleComponent = viewModel.environment::toggleComponentExpanded,
                onClose = viewModel.environment::closePanel,
                onRefresh = viewModel.environment::refreshEnvironments,
                onOpenInManager = onOpenEnvironmentManager,
                activeEnvironmentSummary = activeEnvSummary,
            )
        },
    )
}
