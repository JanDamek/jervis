package com.jervis.ui.model

import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

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
