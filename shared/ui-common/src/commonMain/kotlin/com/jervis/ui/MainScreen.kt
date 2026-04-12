package com.jervis.ui

import com.jervis.ui.queue.OrchestratorProgressInfo
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jervis.di.JervisRepository
import com.jervis.dto.chat.CompressionBoundaryDto
import com.jervis.dto.graph.TaskGraphDto
import com.jervis.dto.task.PendingTaskDto
import com.jervis.dto.ui.ChatMessage
import com.jervis.service.meeting.IJobLogsService
import com.jervis.ui.chat.ChatTaskSidebar
import com.jervis.ui.chat.ChatViewModel
import com.jervis.ui.design.COMPACT_BREAKPOINT_DP
import com.jervis.ui.design.JHorizontalSplitLayout
import com.jervis.ui.model.PendingMessageInfo
import com.jervis.ui.util.PickedFile
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow

/**
 * Main screen for Jervis – chat content area.
 * Client/project selectors and menu are now in PersistentTopBar.
 * AgentStatusRow is now in PersistentTopBar icon.
 */
@Composable
fun MainScreenView(
    selectedClientId: String?,
    selectedProjectId: String?,
    chatMessages: List<ChatMessage>,
    inputText: String,
    isLoading: Boolean,
    isOffline: Boolean = false,
    hasMore: Boolean = false,
    isLoadingMore: Boolean = false,
    compressionBoundaries: List<CompressionBoundaryDto> = emptyList(),
    attachments: List<PickedFile> = emptyList(),
    queueSize: Int = 0,
    onInputChanged: (String) -> Unit,
    onSendClick: () -> Unit,
    onEditMessage: (String) -> Unit = {},
    onReplyToTask: (taskId: String) -> Unit = {},
    onSendReply: (taskId: String, text: String) -> Unit = { _, _ -> },
    onDismissTask: (taskId: String) -> Unit = {},
    onDismissAllTasks: () -> Unit = {},
    onLoadMore: () -> Unit = {},
    onAttachFile: () -> Unit = {},
    onRemoveAttachment: (Int) -> Unit = {},
    pendingMessageInfo: PendingMessageInfo? = null,
    onRetryPending: () -> Unit = {},
    onCancelPending: () -> Unit = {},
    approvalRequest: ChatViewModel.ApprovalRequest? = null,
    onApproveOnce: () -> Unit = {},
    onApproveAlways: () -> Unit = {},
    onDenyAction: () -> Unit = {},
    workspaceInfo: MainViewModel.WorkspaceInfo? = null,
    onRetryWorkspace: () -> Unit = {},
    orchestratorHealthy: Boolean = true,
    orchestratorProgress: OrchestratorProgressInfo? = null,
    taskGraphs: Map<String, TaskGraphDto?> = emptyMap(),
    onLoadTaskGraph: (String) -> Unit = {},
    showChat: Boolean = true,
    onToggleChat: () -> Unit = {},
    showTasks: Boolean = false,
    onToggleTasks: () -> Unit = {},
    showNeedReaction: Boolean = false,
    onToggleNeedReaction: () -> Unit = {},
    backgroundMessageCount: Int = 0,
    userTaskCount: Int = 0,
    pendingQuestionCount: Int = 0,
    isRecordingVoice: Boolean = false,
    voiceStatus: String = "",
    voiceAudioLevel: Float = 0f,
    onMicClick: () -> Unit = {},
    onCancelVoice: () -> Unit = {},
    onTtsPlay: (String) -> Unit = {},
    isTtsPlaying: Boolean = false,
    tierOverride: String? = null,
    onTierOverrideChange: (String?) -> Unit = {},
    activeThinkingGraph: TaskGraphDto? = null,
    thinkingGraphPanelVisible: Boolean = false,
    thinkingGraphPanelWidthFraction: Float = 0.35f,
    onThinkingGraphPanelWidthChange: (Float) -> Unit = {},
    thinkingGraphPanelContent: @Composable (isCompact: Boolean) -> Unit = {},
    hasEnvironment: Boolean = false,
    environmentPanelVisible: Boolean = false,
    onToggleEnvironmentPanel: () -> Unit = {},
    panelWidthFraction: Float = 0.35f,
    onPanelWidthChange: (Float) -> Unit = {},
    environmentPanelContent: @Composable (isCompact: Boolean) -> Unit = {},
    jobLogsService: IJobLogsService? = null,
    onNavigateToTask: ((taskId: String) -> Unit)? = null,
    // Phase 5 — chat task sidebar (active conversations list)
    repository: JervisRepository? = null,
    activeChatTaskId: String? = null,
    activeChatTaskName: String? = null,
    onChatTaskSelected: (PendingTaskDto) -> Unit = {},
    onMainChatSelected: () -> Unit = {},
    chatSidebarSplitFraction: Float = 0.22f,
    onChatSidebarSplitChange: (Float) -> Unit = {},
    onMarkActiveTaskDone: () -> Unit = {},
    onReopenActiveTask: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize().imePadding()) {
        val isCompact = maxWidth < COMPACT_BREAKPOINT_DP.dp

        when {
            // Compact + panel visible -> show panel full-screen
            isCompact && environmentPanelVisible -> {
                environmentPanelContent(true)
            }
            // Expanded + panel visible -> split layout
            !isCompact && environmentPanelVisible -> {
                JHorizontalSplitLayout(
                    splitFraction = 1f - panelWidthFraction,
                    onSplitChange = { onPanelWidthChange(1f - it) },
                    minFraction = 0.5f,
                    maxFraction = 0.8f,
                    leftContent = { _ ->
                        ChatContent(
                            selectedClientId = selectedClientId,
                            selectedProjectId = selectedProjectId,
                            chatMessages = chatMessages,
                            inputText = inputText,
                            isLoading = isLoading,
                            isOffline = isOffline,
                            hasMore = hasMore,
                            isLoadingMore = isLoadingMore,
                            compressionBoundaries = compressionBoundaries,
                            attachments = attachments,
                            queueSize = queueSize,
                            onInputChanged = onInputChanged,
                            onSendClick = onSendClick,
                            onEditMessage = onEditMessage,
                            onReplyToTask = onReplyToTask,
                            onSendReply = onSendReply,
                            onDismissTask = onDismissTask,
                            onDismissAllTasks = onDismissAllTasks,
                            onLoadMore = onLoadMore,
                            onAttachFile = onAttachFile,
                            onRemoveAttachment = onRemoveAttachment,
                            pendingMessageInfo = pendingMessageInfo,
                            onRetryPending = onRetryPending,
                            onCancelPending = onCancelPending,
                            approvalRequest = approvalRequest,
                            onApproveOnce = onApproveOnce,
                            onApproveAlways = onApproveAlways,
                            onDenyAction = onDenyAction,
                            workspaceInfo = workspaceInfo,
                            onRetryWorkspace = onRetryWorkspace,
                            orchestratorHealthy = orchestratorHealthy,
                            orchestratorProgress = orchestratorProgress,
                            taskGraphs = taskGraphs,
                            onLoadTaskGraph = onLoadTaskGraph,
                            showChat = showChat,
                            onToggleChat = onToggleChat,
                            showTasks = showTasks,
                            onToggleTasks = onToggleTasks,
                            showNeedReaction = showNeedReaction,
                            onToggleNeedReaction = onToggleNeedReaction,
                            backgroundMessageCount = backgroundMessageCount,
                            userTaskCount = userTaskCount,
                            pendingQuestionCount = pendingQuestionCount,
                            isRecordingVoice = isRecordingVoice,
                            voiceStatus = voiceStatus,
                            voiceAudioLevel = voiceAudioLevel,
                            onMicClick = onMicClick,
                            onTtsPlay = onTtsPlay,
                            isTtsPlaying = isTtsPlaying,
                            tierOverride = tierOverride,
                            onTierOverrideChange = onTierOverrideChange,
                            onNavigateToTask = onNavigateToTask,
                            activeChatTaskName = activeChatTaskName,
                            onReturnToMainChat = onMainChatSelected,
                            onMarkActiveTaskDone = onMarkActiveTaskDone,
                            onReopenActiveTask = onReopenActiveTask,
                            modifier = Modifier.fillMaxSize(),
                        )
                    },
                    rightContent = { _ ->
                        environmentPanelContent(false)
                    },
                )
            }
            // Compact + thinking graph panel -> full-screen
            isCompact && thinkingGraphPanelVisible && activeThinkingGraph != null -> {
                thinkingGraphPanelContent(true)
            }
            // Expanded + thinking graph panel -> split layout (draggable)
            !isCompact && thinkingGraphPanelVisible && activeThinkingGraph != null -> {
                JHorizontalSplitLayout(
                    splitFraction = 1f - thinkingGraphPanelWidthFraction,
                    onSplitChange = { onThinkingGraphPanelWidthChange(1f - it) },
                    minFraction = 0.5f,
                    maxFraction = 0.8f,
                    leftContent = { _ ->
                        ChatContent(
                            selectedClientId = selectedClientId,
                            selectedProjectId = selectedProjectId,
                            chatMessages = chatMessages,
                            inputText = inputText,
                            isLoading = isLoading,
                            isOffline = isOffline,
                            hasMore = hasMore,
                            isLoadingMore = isLoadingMore,
                            compressionBoundaries = compressionBoundaries,
                            attachments = attachments,
                            queueSize = queueSize,
                            onInputChanged = onInputChanged,
                            onSendClick = onSendClick,
                            onEditMessage = onEditMessage,
                            onReplyToTask = onReplyToTask,
                            onSendReply = onSendReply,
                            onDismissTask = onDismissTask,
                            onDismissAllTasks = onDismissAllTasks,
                            onLoadMore = onLoadMore,
                            onAttachFile = onAttachFile,
                            onRemoveAttachment = onRemoveAttachment,
                            pendingMessageInfo = pendingMessageInfo,
                            onRetryPending = onRetryPending,
                            onCancelPending = onCancelPending,
                            approvalRequest = approvalRequest,
                            onApproveOnce = onApproveOnce,
                            onApproveAlways = onApproveAlways,
                            onDenyAction = onDenyAction,
                            workspaceInfo = workspaceInfo,
                            onRetryWorkspace = onRetryWorkspace,
                            orchestratorHealthy = orchestratorHealthy,
                            orchestratorProgress = orchestratorProgress,
                            taskGraphs = taskGraphs,
                            onLoadTaskGraph = onLoadTaskGraph,
                            jobLogsService = jobLogsService,
                            showChat = showChat,
                            onToggleChat = onToggleChat,
                            showTasks = showTasks,
                            onToggleTasks = onToggleTasks,
                            showNeedReaction = showNeedReaction,
                            onToggleNeedReaction = onToggleNeedReaction,
                            backgroundMessageCount = backgroundMessageCount,
                            userTaskCount = userTaskCount,
                            pendingQuestionCount = pendingQuestionCount,
                            isRecordingVoice = isRecordingVoice,
                            voiceStatus = voiceStatus,
                            voiceAudioLevel = voiceAudioLevel,
                            onMicClick = onMicClick,
                            onTtsPlay = onTtsPlay,
                            isTtsPlaying = isTtsPlaying,
                            tierOverride = tierOverride,
                            onTierOverrideChange = onTierOverrideChange,
                            onNavigateToTask = onNavigateToTask,
                            activeChatTaskName = activeChatTaskName,
                            onReturnToMainChat = onMainChatSelected,
                            onMarkActiveTaskDone = onMarkActiveTaskDone,
                            onReopenActiveTask = onReopenActiveTask,
                            modifier = Modifier.fillMaxSize(),
                        )
                    },
                    rightContent = { _ ->
                        thinkingGraphPanelContent(false)
                    },
                )
            }
            // No panel, no map -> normal chat
            // Phase 5: on expanded screens, show ChatTaskSidebar (240dp) on
            // the left + ChatContent on the right. On compact screens, keep
            // the full-screen chat as before — sidebar comes via overlay
            // later.
            else -> {
                val chatComposable: @Composable () -> Unit = {
                    ChatContent(
                        selectedClientId = selectedClientId,
                        selectedProjectId = selectedProjectId,
                        chatMessages = chatMessages,
                        inputText = inputText,
                        isLoading = isLoading,
                        hasMore = hasMore,
                        isLoadingMore = isLoadingMore,
                        compressionBoundaries = compressionBoundaries,
                        attachments = attachments,
                        queueSize = queueSize,
                        onInputChanged = onInputChanged,
                        onSendClick = onSendClick,
                        onEditMessage = onEditMessage,
                        onReplyToTask = onReplyToTask,
                        onSendReply = onSendReply,
                        onDismissTask = onDismissTask,
                        onDismissAllTasks = onDismissAllTasks,
                        onLoadMore = onLoadMore,
                        onAttachFile = onAttachFile,
                        onRemoveAttachment = onRemoveAttachment,
                        pendingMessageInfo = pendingMessageInfo,
                        onRetryPending = onRetryPending,
                        onCancelPending = onCancelPending,
                        approvalRequest = approvalRequest,
                        onApproveOnce = onApproveOnce,
                        onApproveAlways = onApproveAlways,
                        onDenyAction = onDenyAction,
                        workspaceInfo = workspaceInfo,
                        onRetryWorkspace = onRetryWorkspace,
                        orchestratorHealthy = orchestratorHealthy,
                        orchestratorProgress = orchestratorProgress,
                        taskGraphs = taskGraphs,
                        onLoadTaskGraph = onLoadTaskGraph,
                        jobLogsService = jobLogsService,
                        showChat = showChat,
                        onToggleChat = onToggleChat,
                        showTasks = showTasks,
                        onToggleTasks = onToggleTasks,
                        showNeedReaction = showNeedReaction,
                        onToggleNeedReaction = onToggleNeedReaction,
                        backgroundMessageCount = backgroundMessageCount,
                        userTaskCount = userTaskCount,
                        pendingQuestionCount = pendingQuestionCount,
                        isRecordingVoice = isRecordingVoice,
                        voiceStatus = voiceStatus,
                        voiceAudioLevel = voiceAudioLevel,
                        onMicClick = onMicClick,
                        onCancelVoice = onCancelVoice,
                        onTtsPlay = onTtsPlay,
                        isTtsPlaying = isTtsPlaying,
                        tierOverride = tierOverride,
                        onTierOverrideChange = onTierOverrideChange,
                        onNavigateToTask = onNavigateToTask,
                        activeChatTaskName = activeChatTaskName,
                        onReturnToMainChat = onMainChatSelected,
                        onMarkActiveTaskDone = onMarkActiveTaskDone,
                        onReopenActiveTask = onReopenActiveTask,
                        modifier = Modifier.fillMaxSize(),
                    )
                }

                when {
                    // Expanded (≥600dp): resizable left rail + chat
                    !isCompact && repository != null -> {
                        JHorizontalSplitLayout(
                            splitFraction = chatSidebarSplitFraction,
                            onSplitChange = onChatSidebarSplitChange,
                            minFraction = 0.15f,
                            maxFraction = 0.5f,
                            leftContent = { mod ->
                                ChatTaskSidebar(
                                    repository = repository,
                                    activeTaskId = activeChatTaskId,
                                    onTaskSelected = onChatTaskSelected,
                                    onMainChatSelected = onMainChatSelected,
                                    modifier = mod,
                                )
                            },
                            rightContent = { mod ->
                                Box(modifier = mod) { chatComposable() }
                            },
                        )
                    }
                    // Compact (<600dp, mobile/watch): full-screen chat with
                    // overlay drawer holding the same ChatTaskSidebar.
                    isCompact && repository != null -> {
                        val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
                        val coroutineScope = rememberCoroutineScope()
                        ModalNavigationDrawer(
                            drawerState = drawerState,
                            drawerContent = {
                                ModalDrawerSheet(modifier = Modifier.width(280.dp)) {
                                    ChatTaskSidebar(
                                        repository = repository,
                                        activeTaskId = activeChatTaskId,
                                        onTaskSelected = { task ->
                                            onChatTaskSelected(task)
                                            coroutineScope.launch { drawerState.close() }
                                        },
                                        onMainChatSelected = {
                                            onMainChatSelected()
                                            coroutineScope.launch { drawerState.close() }
                                        },
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                }
                            },
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            // Floating "open drawer" button anchored top-left of the chat
                            Box(modifier = Modifier.fillMaxSize()) {
                                chatComposable()
                                IconButton(
                                    onClick = { coroutineScope.launch { drawerState.open() } },
                                    modifier = Modifier
                                        .align(Alignment.TopStart)
                                        .padding(8.dp),
                                ) {
                                    Icon(
                                        Icons.AutoMirrored.Filled.List,
                                        contentDescription = "Otevřít seznam úloh",
                                    )
                                }
                            }
                        }
                    }
                    // Fallback when no repository (test/preview): plain chat
                    else -> {
                        chatComposable()
                    }
                }
            }
        }
    }
}

/**
 * Chat content area – messages, input. No selectors (moved to PersistentTopBar).
 */
@Composable
private fun ChatContent(
    selectedClientId: String?,
    selectedProjectId: String?,
    chatMessages: List<ChatMessage>,
    inputText: String,
    isLoading: Boolean,
    isOffline: Boolean = false,
    hasMore: Boolean,
    isLoadingMore: Boolean,
    compressionBoundaries: List<CompressionBoundaryDto>,
    attachments: List<PickedFile>,
    queueSize: Int,
    onInputChanged: (String) -> Unit,
    onSendClick: () -> Unit,
    onEditMessage: (String) -> Unit,
    onReplyToTask: (taskId: String) -> Unit = {},
    onSendReply: (taskId: String, text: String) -> Unit = { _, _ -> },
    onDismissTask: (taskId: String) -> Unit = {},
    onDismissAllTasks: () -> Unit = {},
    onLoadMore: () -> Unit,
    onAttachFile: () -> Unit,
    onRemoveAttachment: (Int) -> Unit,
    pendingMessageInfo: PendingMessageInfo? = null,
    onRetryPending: () -> Unit = {},
    onCancelPending: () -> Unit = {},
    approvalRequest: ChatViewModel.ApprovalRequest? = null,
    onApproveOnce: () -> Unit = {},
    onApproveAlways: () -> Unit = {},
    onDenyAction: () -> Unit = {},
    workspaceInfo: MainViewModel.WorkspaceInfo? = null,
    onRetryWorkspace: () -> Unit = {},
    orchestratorHealthy: Boolean = true,
    orchestratorProgress: OrchestratorProgressInfo? = null,
    taskGraphs: Map<String, TaskGraphDto?> = emptyMap(),
    onLoadTaskGraph: (String) -> Unit = {},
    jobLogsService: IJobLogsService? = null,
    showChat: Boolean = true,
    onToggleChat: () -> Unit = {},
    showTasks: Boolean = false,
    onToggleTasks: () -> Unit = {},
    showNeedReaction: Boolean = false,
    onToggleNeedReaction: () -> Unit = {},
    backgroundMessageCount: Int = 0,
    userTaskCount: Int = 0,
    pendingQuestionCount: Int = 0,
    isRecordingVoice: Boolean = false,
    voiceStatus: String = "",
    voiceAudioLevel: Float = 0f,
    onMicClick: () -> Unit = {},
    onCancelVoice: () -> Unit = {},
    onTtsPlay: (String) -> Unit = {},
    isTtsPlaying: Boolean = false,
    tierOverride: String? = null,
    onTierOverrideChange: (String?) -> Unit = {},
    onNavigateToTask: ((taskId: String) -> Unit)? = null,
    // Phase 5 — active per-task conversation indicator
    activeChatTaskName: String? = null,
    onReturnToMainChat: () -> Unit = {},
    onMarkActiveTaskDone: () -> Unit = {},
    onReopenActiveTask: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        // Workspace status banner
        if (workspaceInfo != null) {
            WorkspaceBanner(
                info = workspaceInfo,
                onRetry = onRetryWorkspace,
            )
        }

        // Orchestrator health banner
        if (!orchestratorHealthy) {
            OrchestratorHealthBanner()
        }

        // Phase 5 — active per-task conversation breadcrumb. Only visible
        // when the user has drilled into a task's chat from the sidebar.
        // Left side of the bar = back arrow (return to main chat).
        // Right side = action buttons: mark done / reopen.
        if (activeChatTaskName != null) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.primaryContainer,
                tonalElevation = 1.dp,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .clickable { onReturnToMainChat() }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Zpět na hlavní chat",
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "↳ $activeChatTaskName",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    TextButton(
                        onClick = onReopenActiveTask,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    ) {
                        Text(
                            "Otevřít znovu",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                    TextButton(
                        onClick = onMarkActiveTaskDone,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    ) {
                        Text(
                            "Označit hotové",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }
            }
        }

        // Filter chips — always visible
        Row(
            Modifier.padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilterChip(
                selected = showChat,
                onClick = onToggleChat,
                modifier = Modifier.height(28.dp),
                label = { Text("Chat", style = MaterialTheme.typography.labelSmall) },
            )
            FilterChip(
                selected = showTasks,
                onClick = onToggleTasks,
                modifier = Modifier.height(28.dp),
                label = { Text("Tasky", style = MaterialTheme.typography.labelSmall) },
            )
            @OptIn(ExperimentalMaterial3Api::class)
            BadgedBox(
                badge = {
                    val totalBadge = userTaskCount + pendingQuestionCount
                    if (totalBadge > 0) {
                        Badge { Text("$totalBadge") }
                    }
                },
            ) {
                FilterChip(
                    selected = showNeedReaction,
                    onClick = onToggleNeedReaction,
                    modifier = Modifier.height(28.dp),
                    label = {
                        Text("K reakci", style = MaterialTheme.typography.labelSmall)
                    },
                )
            }
            if (showNeedReaction && userTaskCount > 0) {
                Spacer(Modifier.weight(1f))
                TextButton(
                    onClick = onDismissAllTasks,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    modifier = Modifier.height(28.dp),
                ) {
                    Icon(
                        Icons.Default.VisibilityOff,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("Ignorovat vše", style = MaterialTheme.typography.labelSmall)
                }
            }

            // Tier override toggle — right-aligned
            if (!(showNeedReaction && userTaskCount > 0)) {
                Spacer(Modifier.weight(1f))
            }
            TierToggle(
                selected = tierOverride,
                onSelect = onTierOverrideChange,
            )
        }

        // Chat area
        ChatArea(
            messages = chatMessages,
            hasMore = hasMore,
            isLoadingMore = isLoadingMore,
            compressionBoundaries = compressionBoundaries,
            orchestratorProgress = orchestratorProgress,
            onLoadMore = onLoadMore,
            onEditMessage = onEditMessage,
            onReplyToTask = onReplyToTask,
            onSendReply = onSendReply,
            onDismissTask = onDismissTask,
            onTtsPlay = onTtsPlay,
            isTtsPlaying = isTtsPlaying,
            taskGraphs = taskGraphs,
            onLoadTaskGraph = onLoadTaskGraph,
            onNavigateToTask = onNavigateToTask,
            jobLogsService = jobLogsService,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        )

        // Pending message banner — shown when message failed to send
        if (pendingMessageInfo != null) {
            PendingMessageBanner(
                info = pendingMessageInfo,
                onRetry = onRetryPending,
                onCancel = onCancelPending,
            )
        }

        // Approval request banner — shown when chat tool needs user approval
        if (approvalRequest != null) {
            ApprovalBanner(
                request = approvalRequest,
                onApproveOnce = onApproveOnce,
                onApproveAlways = onApproveAlways,
                onDeny = onDenyAction,
            )
        }

        HorizontalDivider()

        // Input area — always enabled, messages queue when offline
        InputArea(
            inputText = inputText,
            onInputChanged = onInputChanged,
            onSendClick = onSendClick,
            enabled = true,
            queueSize = queueSize,
            attachments = attachments,
            onAttachFile = onAttachFile,
            onRemoveAttachment = onRemoveAttachment,
            isRecordingVoice = isRecordingVoice,
            voiceStatus = voiceStatus,
            voiceAudioLevel = voiceAudioLevel,
            onMicClick = onMicClick,
            onCancelVoice = onCancelVoice,
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
        )
    }
}

/**
 * Banner shown when a message failed to send and is pending retry.
 * Shows truncated message text, retry countdown, and Retry/Cancel buttons.
 */
@Composable
private fun PendingMessageBanner(
    info: PendingMessageInfo,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.errorContainer,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Zpráva nebyla odeslána",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                val detail = buildString {
                    val preview = info.text.take(50)
                    append("\"$preview\"")
                    if (info.text.length > 50) append("...")
                    if (info.isAutoRetrying && info.nextRetryInSeconds != null) {
                        append(" — Další pokus za ${info.nextRetryInSeconds}s")
                    } else if (!info.isRetryable) {
                        append(" — ${info.errorMessage ?: "Chyba serveru"}")
                    } else if (info.attemptCount >= 4) {
                        append(" — Automatické pokusy vyčerpány")
                    }
                }
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.7f),
                    maxLines = 1,
                )
            }
            TextButton(onClick = onRetry) { Text("Znovu") }
            TextButton(onClick = onCancel) { Text("Zrušit") }
        }
    }
}

/**
 * Banner shown when workspace clone failed or is in progress.
 */
@Composable
private fun WorkspaceBanner(
    info: MainViewModel.WorkspaceInfo,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isCloning = info.status == "CLONING"
    val containerColor = if (isCloning) {
        MaterialTheme.colorScheme.secondaryContainer
    } else {
        MaterialTheme.colorScheme.errorContainer
    }
    val contentColor = if (isCloning) {
        MaterialTheme.colorScheme.onSecondaryContainer
    } else {
        MaterialTheme.colorScheme.onErrorContainer
    }
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = containerColor,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (isCloning) Icons.Default.Refresh else Icons.Default.Warning,
                contentDescription = null,
                tint = if (isCloning) contentColor else MaterialTheme.colorScheme.error,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (isCloning) "Probíhá příprava prostředí..." else "Příprava prostředí selhala",
                    style = MaterialTheme.typography.labelLarge,
                    color = contentColor,
                )
                if (!isCloning && info.error != null) {
                    Text(
                        text = info.error,
                        style = MaterialTheme.typography.bodySmall,
                        color = contentColor.copy(alpha = 0.7f),
                        maxLines = 1,
                    )
                }
            }
            if (!isCloning) {
                TextButton(onClick = onRetry) { Text("Zkusit znovu") }
            }
        }
    }
}

/**
 * Banner shown when the Python orchestrator is unhealthy (circuit breaker OPEN).
 */
@Composable
private fun OrchestratorHealthBanner(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.tertiaryContainer,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "Orchestrátor není dostupný. Tasky budou zpracovány po obnovení.",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
        }
    }
}

/**
 * Banner shown when a chat tool action needs user approval.
 * Three options: approve once, approve always (for this session), deny.
 */
@Composable
private fun ApprovalBanner(
    request: ChatViewModel.ApprovalRequest,
    onApproveOnce: () -> Unit,
    onApproveAlways: () -> Unit,
    onDeny: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Akce vyžaduje schválení",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Text(
                    text = "${request.tool}: ${request.preview.take(80)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f),
                    maxLines = 2,
                )
            }
            TextButton(onClick = onApproveOnce) { Text("Povolit") }
            TextButton(onClick = onApproveAlways) { Text("Vždy") }
            TextButton(onClick = onDeny) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Zamítnout",
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

/**
 * Tier override toggle — compact segmented button for quick OpenRouter tier switching.
 * null = policy (default from settings), NONE/FREE/PAID/PREMIUM = override.
 */
@Composable
private fun TierToggle(
    selected: String?,
    onSelect: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tiers = listOf(null to "Auto", "NONE" to "GPU", "FREE" to "Free", "PAID" to "Paid", "PREMIUM" to "Pro")
    val shape = RoundedCornerShape(6.dp)

    Row(
        modifier = modifier
            .height(26.dp)
            .clip(shape)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        tiers.forEachIndexed { index, (tier, label) ->
            val isSelected = selected == tier
            val bgColor = if (isSelected) {
                when (tier) {
                    null -> MaterialTheme.colorScheme.primaryContainer
                    "NONE" -> MaterialTheme.colorScheme.surfaceVariant
                    "FREE" -> MaterialTheme.colorScheme.tertiaryContainer
                    "PAID" -> MaterialTheme.colorScheme.secondaryContainer
                    "PREMIUM" -> MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                    else -> MaterialTheme.colorScheme.surfaceVariant
                }
            } else {
                MaterialTheme.colorScheme.surface
            }
            val textColor = if (isSelected) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
            }

            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = textColor,
                modifier = Modifier
                    .background(bgColor)
                    .clickable { onSelect(tier) }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
}
