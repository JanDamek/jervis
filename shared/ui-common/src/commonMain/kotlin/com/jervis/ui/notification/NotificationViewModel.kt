package com.jervis.ui.notification

import com.jervis.dto.events.JervisEvent
import com.jervis.dto.user.TaskRoutingMode
import com.jervis.di.JervisRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * ViewModel for user task notifications — badge count, approval/deny dialog, platform notifications.
 *
 * Receives events from MainViewModel.handleGlobalEvent() via handleUserTaskCreated/Cancelled.
 */
class NotificationViewModel(
    private val repository: JervisRepository,
    private val selectedClientId: StateFlow<String?>,
) {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob() + CoroutineExceptionHandler { _, e ->
        if (e !is CancellationException) {
            println("NotificationViewModel: uncaught exception: ${e::class.simpleName}: ${e.message}")
        }
    })

    val notificationManager = PlatformNotificationManager()

    private val _notifications = MutableStateFlow<List<JervisEvent>>(emptyList())
    val notifications: StateFlow<List<JervisEvent>> = _notifications.asStateFlow()

    private val _userTaskCount = MutableStateFlow(0)
    val userTaskCount: StateFlow<Int> = _userTaskCount.asStateFlow()

    private val _userTaskDialogEvent = MutableStateFlow<JervisEvent.UserTaskCreated?>(null)
    val userTaskDialogEvent: StateFlow<JervisEvent.UserTaskCreated?> = _userTaskDialogEvent.asStateFlow()

    init {
        notificationManager.initialize()

        // Collect notification action results from platform-specific handlers
        scope.launch {
            NotificationActionChannel.actions.collect { result ->
                when (result.action) {
                    NotificationAction.APPROVE -> {
                        try {
                            repository.userTasks.sendToAgent(
                                taskId = result.taskId,
                                routingMode = TaskRoutingMode.DIRECT_TO_AGENT,
                                additionalInput = null,
                            )
                            refreshUserTaskCount()
                        } catch (e: Exception) {
                            println("Failed to approve task ${result.taskId}: ${e.message}")
                        }
                        _userTaskDialogEvent.value = null
                    }
                    NotificationAction.DENY -> {
                        // Deny from notification → show in-app dialog for reason input
                    }
                    NotificationAction.REPLY -> {
                        // Inline reply (e.g. MFA code from notification RemoteInput)
                        val text = result.replyText
                        if (!text.isNullOrBlank()) {
                            try {
                                repository.userTasks.sendToAgent(
                                    taskId = result.taskId,
                                    routingMode = TaskRoutingMode.DIRECT_TO_AGENT,
                                    additionalInput = text,
                                )
                                refreshUserTaskCount()
                            } catch (e: Exception) {
                                println("Failed to reply to task ${result.taskId}: ${e.message}")
                            }
                            _userTaskDialogEvent.value = null
                        }
                    }
                    NotificationAction.OPEN -> {
                        // Navigate to user tasks — handled by UI layer
                    }
                }
            }
        }
    }

    fun refreshUserTaskCount() {
        val clientId = selectedClientId.value?.takeIf { it != "__global__" } ?: return
        scope.launch {
            try {
                val countDto = repository.userTasks.activeCount(clientId)
                _userTaskCount.value = countDto.activeCount
            } catch (e: Exception) {
                if (e is CancellationException) throw e
            }
        }
    }

    fun approveTask(taskId: String, onError: (String) -> Unit = {}) {
        scope.launch {
            try {
                repository.userTasks.sendToAgent(
                    taskId = taskId,
                    routingMode = TaskRoutingMode.DIRECT_TO_AGENT,
                    additionalInput = null,
                )
                _userTaskDialogEvent.value = null
                notificationManager.cancelNotification(taskId)
                refreshUserTaskCount()
            } catch (e: Exception) {
                onError("Schválení selhalo: ${e.message}")
            }
        }
    }

    fun denyTask(taskId: String, reason: String, onError: (String) -> Unit = {}) {
        scope.launch {
            try {
                repository.userTasks.sendToAgent(
                    taskId = taskId,
                    routingMode = TaskRoutingMode.DIRECT_TO_AGENT,
                    additionalInput = reason,
                )
                _userTaskDialogEvent.value = null
                notificationManager.cancelNotification(taskId)
                refreshUserTaskCount()
            } catch (e: Exception) {
                onError("Zamítnutí selhalo: ${e.message}")
            }
        }
    }

    fun replyToTask(taskId: String, reply: String, onError: (String) -> Unit = {}) {
        scope.launch {
            try {
                repository.userTasks.sendToAgent(
                    taskId = taskId,
                    routingMode = TaskRoutingMode.DIRECT_TO_AGENT,
                    additionalInput = reply,
                )
                _userTaskDialogEvent.value = null
                notificationManager.cancelNotification(taskId)
                refreshUserTaskCount()
            } catch (e: Exception) {
                onError("Odeslání odpovědi selhalo: ${e.message}")
            }
        }
    }

    fun discardTask(taskId: String, onError: (String) -> Unit = {}) {
        scope.launch {
            try {
                repository.userTasks.cancel(taskId)
                notificationManager.cancelNotification(taskId)
                refreshUserTaskCount()
            } catch (e: Exception) {
                onError("Zahození selhalo: ${e.message}")
            } finally {
                _userTaskDialogEvent.value = null
            }
        }
    }

    fun retryTask(taskId: String, onError: (String) -> Unit = {}) {
        scope.launch {
            try {
                // Re-route to agent without additional input → agent re-processes from scratch
                repository.userTasks.sendToAgent(
                    taskId = taskId,
                    routingMode = TaskRoutingMode.DIRECT_TO_AGENT,
                    additionalInput = null,
                )
                notificationManager.cancelNotification(taskId)
                refreshUserTaskCount()
            } catch (e: Exception) {
                onError("Opakování selhalo: ${e.message}")
            } finally {
                _userTaskDialogEvent.value = null
            }
        }
    }

    fun dismissUserTaskDialog() {
        _userTaskDialogEvent.value = null
    }

    /**
     * Handle UserTaskCreated event from global event stream.
     */
    fun handleUserTaskCreated(event: JervisEvent.UserTaskCreated) {
        _notifications.value = _notifications.value + event
        refreshUserTaskCount()
        val notifTitle = when {
            event.isError -> "Úloha selhala"
            event.isApproval -> "Schválení vyžadováno"
            else -> "Úloha potřebuje odpověď"
        }
        notificationManager.showNotification(
            title = notifTitle,
            body = event.title,
            taskId = event.taskId,
            isApproval = event.isApproval,
            interruptAction = event.interruptAction,
        )
        _userTaskDialogEvent.value = event
    }

    /**
     * Handle UserTaskCancelled event from global event stream.
     */
    fun handleUserTaskCancelled(event: JervisEvent.UserTaskCancelled) {
        _notifications.value = _notifications.value.filter {
            !(it is JervisEvent.UserTaskCreated && it.taskId == event.taskId)
        }
        refreshUserTaskCount()
        notificationManager.cancelNotification(event.taskId)
        if (_userTaskDialogEvent.value?.taskId == event.taskId) {
            _userTaskDialogEvent.value = null
        }
    }
}
