package com.jervis.ui

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jervis.dto.CompressionBoundaryDto
import com.jervis.dto.ui.ChatMessage
import com.jervis.ui.design.COMPACT_BREAKPOINT_DP
import com.jervis.ui.design.JHorizontalSplitLayout
import com.jervis.ui.model.PendingMessageInfo
import com.jervis.ui.util.PickedFile

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
    hasMore: Boolean = false,
    isLoadingMore: Boolean = false,
    compressionBoundaries: List<CompressionBoundaryDto> = emptyList(),
    attachments: List<PickedFile> = emptyList(),
    queueSize: Int = 0,
    runningProjectId: String? = null,
    onInputChanged: (String) -> Unit,
    onSendClick: () -> Unit,
    onEditMessage: (String) -> Unit = {},
    onLoadMore: () -> Unit = {},
    onAttachFile: () -> Unit = {},
    onRemoveAttachment: (Int) -> Unit = {},
    pendingMessageInfo: PendingMessageInfo? = null,
    onRetryPending: () -> Unit = {},
    onCancelPending: () -> Unit = {},
    workspaceInfo: MainViewModel.WorkspaceInfo? = null,
    onRetryWorkspace: () -> Unit = {},
    orchestratorHealthy: Boolean = true,
    hasEnvironment: Boolean = false,
    environmentPanelVisible: Boolean = false,
    onToggleEnvironmentPanel: () -> Unit = {},
    panelWidthFraction: Float = 0.35f,
    onPanelWidthChange: (Float) -> Unit = {},
    environmentPanelContent: @Composable (isCompact: Boolean) -> Unit = {},
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
                            hasMore = hasMore,
                            isLoadingMore = isLoadingMore,
                            compressionBoundaries = compressionBoundaries,
                            attachments = attachments,
                            queueSize = queueSize,
                            runningProjectId = runningProjectId,
                            onInputChanged = onInputChanged,
                            onSendClick = onSendClick,
                            onEditMessage = onEditMessage,
                            onLoadMore = onLoadMore,
                            onAttachFile = onAttachFile,
                            onRemoveAttachment = onRemoveAttachment,
                            pendingMessageInfo = pendingMessageInfo,
                            onRetryPending = onRetryPending,
                            onCancelPending = onCancelPending,
                            workspaceInfo = workspaceInfo,
                            onRetryWorkspace = onRetryWorkspace,
                            orchestratorHealthy = orchestratorHealthy,
                            modifier = Modifier.fillMaxSize(),
                        )
                    },
                    rightContent = { _ ->
                        environmentPanelContent(false)
                    },
                )
            }
            // No panel -> normal chat
            else -> {
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
                    runningProjectId = runningProjectId,
                    onInputChanged = onInputChanged,
                    onSendClick = onSendClick,
                    onEditMessage = onEditMessage,
                    onLoadMore = onLoadMore,
                    onAttachFile = onAttachFile,
                    onRemoveAttachment = onRemoveAttachment,
                    pendingMessageInfo = pendingMessageInfo,
                    onRetryPending = onRetryPending,
                    onCancelPending = onCancelPending,
                    workspaceInfo = workspaceInfo,
                    onRetryWorkspace = onRetryWorkspace,
                    orchestratorHealthy = orchestratorHealthy,
                    modifier = Modifier.fillMaxSize(),
                )
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
    hasMore: Boolean,
    isLoadingMore: Boolean,
    compressionBoundaries: List<CompressionBoundaryDto>,
    attachments: List<PickedFile>,
    queueSize: Int,
    runningProjectId: String?,
    onInputChanged: (String) -> Unit,
    onSendClick: () -> Unit,
    onEditMessage: (String) -> Unit,
    onLoadMore: () -> Unit,
    onAttachFile: () -> Unit,
    onRemoveAttachment: (Int) -> Unit,
    pendingMessageInfo: PendingMessageInfo? = null,
    onRetryPending: () -> Unit = {},
    onCancelPending: () -> Unit = {},
    workspaceInfo: MainViewModel.WorkspaceInfo? = null,
    onRetryWorkspace: () -> Unit = {},
    orchestratorHealthy: Boolean = true,
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

        // Chat area
        ChatArea(
            messages = chatMessages,
            hasMore = hasMore,
            isLoadingMore = isLoadingMore,
            compressionBoundaries = compressionBoundaries,
            onLoadMore = onLoadMore,
            onEditMessage = onEditMessage,
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

        HorizontalDivider()

        // Input area
        InputArea(
            inputText = inputText,
            onInputChanged = onInputChanged,
            onSendClick = onSendClick,
            enabled = selectedClientId != null && selectedProjectId != null && !isLoading,
            queueSize = queueSize,
            runningProjectId = runningProjectId,
            currentProjectId = selectedProjectId,
            attachments = attachments,
            onAttachFile = onAttachFile,
            onRemoveAttachment = onRemoveAttachment,
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
