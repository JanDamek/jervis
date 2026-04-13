package com.jervis.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.jervis.di.JervisRepository
import com.jervis.ui.MainViewModel
import com.jervis.ui.environment.EnvironmentPanel
import com.jervis.ui.MainScreenView as MainScreenViewInternal

@Composable
fun MainScreen(
    viewModel: MainViewModel,
    repository: JervisRepository? = null,
    onOpenEnvironmentManager: (String) -> Unit = {},
    onNavigateToTask: ((taskId: String) -> Unit)? = null,
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
    val pendingQuestionCount by viewModel.pendingQuestions.pendingCount.collectAsState()

    // No client-side filtering — server returns exactly what UI should display (DB-filtered).
    // Filter mode (CHAT/TASKS/NEED_REACTION) is passed to getChatHistory() on server.
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
    val activeThinkingGraph by viewModel.chat.activeThinkingGraph.collectAsState()
    val detailThinkingGraph by viewModel.chat.detailThinkingGraph.collectAsState()
    val thinkingGraphPanelVisible by viewModel.chat.thinkingGraphPanelVisible.collectAsState()
    val thinkingGraphPanelWidthFraction by viewModel.chat.thinkingGraphPanelWidthFraction.collectAsState()
    val liveLogTaskId by viewModel.chat.liveLogTaskId.collectAsState()
    val activeThoughtContext by viewModel.chat.activeThoughtContext.collectAsState()
    val isRecordingVoice by viewModel.chat.isRecordingVoice.collectAsState()
    val voiceStatus by viewModel.chat.voiceStatus.collectAsState()
    val voiceAudioLevel by viewModel.chat.voiceAudioLevel.collectAsState()
    val isTtsPlaying by viewModel.chat.isTtsPlaying.collectAsState()
    val tierOverride by viewModel.chat.tierOverride.collectAsState()

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
    val environmentLogView by viewModel.environment.logView.collectAsState()

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
        chatMessages = chatMessages,
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
        onDismissTask = viewModel.chat::dismissTask,
        onDismissAllTasks = viewModel.chat::dismissAllTasks,
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
        pendingQuestionCount = pendingQuestionCount,
        isRecordingVoice = isRecordingVoice,
        voiceStatus = voiceStatus,
        voiceAudioLevel = voiceAudioLevel,
        onMicClick = viewModel.chat::toggleVoiceRecording,
        onCancelVoice = viewModel.chat::cancelVoiceRecording,
        onTtsPlay = viewModel.chat::playTts,
        isTtsPlaying = isTtsPlaying,
        tierOverride = tierOverride,
        onTierOverrideChange = viewModel.chat::setTierOverride,
        activeThinkingGraph = activeThinkingGraph,
        thinkingGraphPanelVisible = thinkingGraphPanelVisible,
        thinkingGraphPanelWidthFraction = thinkingGraphPanelWidthFraction,
        onThinkingGraphPanelWidthChange = viewModel.chat::updateThinkingGraphPanelWidthFraction,
        thinkingGraphPanelContent = { isCompact ->
            com.jervis.ui.chat.ThinkingGraphPanel(
                activeMap = activeThinkingGraph,
                isCompact = isCompact,
                onClose = viewModel.chat::closeThinkingGraphPanel,
                detailGraph = detailThinkingGraph,
                liveLogTaskId = liveLogTaskId,
                jobLogsService = viewModel.chat.jobLogsService,
                thoughtContext = activeThoughtContext?.let {
                    com.jervis.ui.chat.ThoughtContextDisplay(
                        formattedContext = it.formattedContext,
                        thoughtCount = it.thoughtCount,
                    )
                },
                onOpenSubGraph = viewModel.chat::openSubGraph,
                onCloseSubGraph = viewModel.chat::closeSubGraph,
                onOpenLiveLog = viewModel.chat::openLiveLog,
                onCloseLiveLog = viewModel.chat::closeLiveLog,
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
                onDeploy = viewModel.environment::deployEnvironment,
                onStop = viewModel.environment::stopEnvironment,
                onViewLogs = viewModel.environment::viewComponentLogs,
                logViewState = environmentLogView,
                onCloseLogView = viewModel.environment::closeLogView,
                activeEnvironmentSummary = activeEnvSummary,
            )
        },
        jobLogsService = viewModel.chat.jobLogsService,
        onNavigateToTask = onNavigateToTask,
        // Phase 5 — chat task sidebar wired to per-task conversation switching
        repository = repository,
        activeChatTaskId = viewModel.chat.activeChatTaskId.collectAsState().value,
        activeChatTaskName = viewModel.chat.activeChatTaskName.collectAsState().value,
        activeChatTaskState = viewModel.chat.activeChatTaskState.collectAsState().value,
        onChatTaskSelected = { task ->
            viewModel.chat.switchToTaskConversation(
                task.id,
                task.taskName.takeIf { it.isNotBlank() && it != "Unnamed Task" }
                    ?: task.sourceLabel.ifBlank { "Úloha" },
            )
        },
        onMainChatSelected = {
            viewModel.chat.switchToMainChat()
        },
        chatSidebarSplitFraction = viewModel.chat.chatSidebarSplitFraction.collectAsState().value,
        onChatSidebarSplitChange = viewModel.chat::updateChatSidebarSplitFraction,
        onMarkActiveTaskDone = { viewModel.chat.markActiveTaskDone() },
        onReopenActiveTask = { viewModel.chat.reopenActiveTask() },
        sidebarRefreshTrigger = viewModel.chat.sidebarRefreshTrigger.collectAsState().value,
        sidebarRemovedTaskIds = viewModel.chat.sidebarRemovedTaskIds.collectAsState().value,
    )
}
