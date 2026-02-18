package com.jervis.ui.model

import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock

/**
 * Single entry in the in-memory agent activity log.
 * Lightweight – only stored since app start, max ~200 entries.
 */
data class AgentActivityEntry(
    val id: Int,
    val time: String,
    val type: Type,
    val description: String,
    val projectName: String? = null,
    val taskType: String? = null,
    val clientId: String? = null,
) {
    enum class Type {
        TASK_STARTED,
        TASK_COMPLETED,
        AGENT_IDLE,
        QUEUE_CHANGED,
    }

    companion object {
        fun formatNow(): String {
            val now = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
            return "${now.hour.toString().padStart(2, '0')}:" +
                "${now.minute.toString().padStart(2, '0')}:" +
                "${now.second.toString().padStart(2, '0')}"
        }
    }
}

/**
 * Represents a pending item in the agent processing queue.
 * Displayed in the AgentWorkloadScreen to show what's waiting.
 * Enhanced with taskId and processingMode for queue management (reorder/move).
 */
data class PendingQueueItem(
    val taskId: String = "",
    val preview: String,
    val projectName: String,
    val taskType: String = "",
    val processingMode: String = "FOREGROUND", // "FOREGROUND" or "BACKGROUND"
    val queuePosition: Int? = null,
)

/**
 * In-memory ring-buffer style activity log. Keeps last [maxSize] entries.
 * Thread-safe via synchronized access from coroutine dispatcher.
 */
class AgentActivityLog(private val maxSize: Int = 200) {
    private val _entries = mutableListOf<AgentActivityEntry>()
    val entries: List<AgentActivityEntry> get() = _entries.toList()

    private var nextId = 1

    fun add(
        type: AgentActivityEntry.Type,
        description: String,
        projectName: String? = null,
        taskType: String? = null,
        clientId: String? = null,
    ): AgentActivityEntry {
        val entry = AgentActivityEntry(
            id = nextId++,
            time = AgentActivityEntry.formatNow(),
            type = type,
            description = description,
            projectName = projectName,
            taskType = taskType,
            clientId = clientId,
        )
        _entries.add(entry)
        if (_entries.size > maxSize) {
            _entries.removeAt(0)
        }
        return entry
    }

    fun clear() {
        _entries.clear()
    }
}

/**
 * Status of a node in the orchestrator pipeline history.
 */
enum class NodeStatus {
    DONE,
    RUNNING,
    PENDING,
}

/**
 * A single node (step) in an orchestrator pipeline execution.
 */
data class NodeEntry(
    val node: String,
    val label: String,
    val status: NodeStatus = NodeStatus.PENDING,
    val durationMs: Long? = null,
)

/**
 * A completed or in-progress task with its orchestrator pipeline nodes.
 * Used in the History section of AgentWorkloadScreen.
 */
data class TaskHistoryEntry(
    val taskId: String,
    val taskPreview: String,
    val projectName: String?,
    val startTime: String,
    val endTime: String? = null,
    val status: String = "running", // "running", "done", "error"
    val nodes: List<NodeEntry> = emptyList(),
)
