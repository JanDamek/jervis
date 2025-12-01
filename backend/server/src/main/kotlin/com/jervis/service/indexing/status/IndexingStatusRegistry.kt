package com.jervis.service.indexing.status

import com.jervis.domain.websocket.WebSocketChannelTypeEnum
import com.jervis.repository.JiraIssueIndexMongoRepository
import com.jervis.service.websocket.WebSocketSessionManager
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.springframework.stereotype.Service
import java.time.Instant

/**
 * Runtime in-memory registry tracking indexing status across all tools.
 * Not persisted. Keeps current run and last run summary with recent items log per tool.
 * Memory Management:
 * - Items limited to MAX_ITEMS_PER_TOOL (100) to prevent heap exhaustion
 * - Old items automatically dropped when limit exceeded
 * - Full error details preserved separately from truncated lastError
 */
@Service
class IndexingStatusRegistry(
    private val webSocketSessionManager: WebSocketSessionManager,
    private val gitCommitRepository: com.jervis.service.git.state.GitCommitRepository,
    private val jiraIssueIndexRepository: JiraIssueIndexMongoRepository,
) {
    private val json = Json { encodeDefaults = true }

    companion object {
        private const val MAX_ITEMS_PER_TOOL = 100
        private const val ERROR_PREVIEW_LENGTH = 500
    }

    enum class ToolStateEnum {
        EMAIL,
        GIT,
        CONFLUENCE,
        JIRA,
    }

    data class ToolState(
        val toolKey: ToolStateEnum,
        var displayName: String,
        var state: State = State.IDLE,
        var runningSince: Instant? = null,
        var processed: Int = 0,
        var errors: Int = 0,
        var lastError: String? = null,
        var lastErrorFull: String? = null,
        var lastRunStartedAt: Instant? = null,
        var lastRunFinishedAt: Instant? = null,
        var reason: String? = null,
        val items: ArrayDeque<Item> = ArrayDeque(),
    )

    enum class State { IDLE, RUNNING }

    data class Item(
        val timestamp: Instant,
        val level: String,
        val message: String,
        val processedDelta: Int? = null,
        val errorsDelta: Int? = null,
        val fullDetails: String? = null, // Full error stacktrace if level=ERROR
    )

    @Serializable
    data class IndexingStatusUpdateEvent(
        val toolKey: ToolStateEnum,
        val displayName: String,
        val state: String,
        val processed: Int,
        val errors: Int,
        val lastError: String? = null,
        val timestamp: String,
    )

    private val mutex = Mutex()
    private val tools = linkedMapOf<ToolStateEnum, ToolState>()

    suspend fun ensureTool(
        toolKey: ToolStateEnum,
        displayName: String,
    ): ToolState =
        mutex.withLock {
            tools.getOrPut(toolKey) { ToolState(toolKey, displayName) }
        }

    suspend fun start(
        toolKey: ToolStateEnum,
        displayName: String,
        message: String? = null,
    ) {
        mutex.withLock {
            val t = tools.getOrPut(toolKey) { ToolState(toolKey, displayName) }
            t.displayName = displayName
            t.state = State.RUNNING
            t.runningSince = Instant.now()
            t.lastRunStartedAt = t.runningSince
            t.processed = 0
            t.errors = 0
            t.lastError = null
            t.lastErrorFull = null
            t.items.clear()
            t.reason = message // use start message as short reason/context
            message?.let { addItem(t, Item(Instant.now(), "INFO", it)) }
            notifyClients(t)
        }
    }

    /**
     * Compute global indexed/new counts for a given tool.
     * Indexed = successfully processed (indexed) items so far.
     * New = items discovered and waiting (NEW state or equivalent).
     */
    suspend fun getIndexedAndNewCounts(toolKey: ToolStateEnum): Pair<Long, Long> =
        when (toolKey) {
            ToolStateEnum.EMAIL -> {
                // TODO: Re-enable after email refactoring
                0L to 0L
            }
            ToolStateEnum.GIT -> {
                val indexed = gitCommitRepository.countByState(com.jervis.service.git.state.GitCommitState.INDEXED)
                val new = gitCommitRepository.countByState(com.jervis.service.git.state.GitCommitState.NEW)
                indexed to new
            }
            ToolStateEnum.CONFLUENCE -> {
                // TODO: Re-enable after confluence refactoring
                0L to 0L
            }
            ToolStateEnum.JIRA -> {
                val indexed = jiraIssueIndexRepository.countIndexedActive()
                val new = jiraIssueIndexRepository.countNewActive()
                indexed to new
            }
        }

    suspend fun progress(
        toolKey: ToolStateEnum,
        processedInc: Int = 1,
        message: String? = null,
    ) {
        mutex.withLock {
            val t = tools[toolKey] ?: return
            t.processed += processedInc
            val msg = message ?: "Processed +$processedInc"
            addItem(t, Item(Instant.now(), "PROGRESS", msg, processedDelta = processedInc))
            // Notify every 10 items to avoid WebSocket spam
            if (t.processed % 10 == 0) {
                notifyClients(t)
            }
        }
    }

    suspend fun info(
        toolKey: ToolStateEnum,
        message: String,
    ) {
        mutex.withLock {
            val t = tools[toolKey] ?: return
            addItem(t, Item(Instant.now(), "INFO", message))
            t.reason = message // update reason when an info is sent
            notifyClients(t)
        }
    }

    suspend fun error(
        toolKey: ToolStateEnum,
        message: String,
        fullStackTrace: String? = null,
    ) {
        mutex.withLock {
            val t = tools[toolKey] ?: return
            t.errors += 1
            t.lastError = message.take(ERROR_PREVIEW_LENGTH)
            t.lastErrorFull = fullStackTrace ?: message
            addItem(t, Item(Instant.now(), "ERROR", message, errorsDelta = 1, fullDetails = fullStackTrace ?: message))
            notifyClients(t)
        }
    }

    suspend fun finish(
        toolKey: ToolStateEnum,
        message: String? = null,
    ) {
        mutex.withLock {
            val t = tools[toolKey] ?: return
            t.lastRunFinishedAt = Instant.now()
            t.state = State.IDLE
            t.runningSince = null
            message?.let { addItem(t, Item(Instant.now(), "INFO", it)) }
            notifyClients(t)
            // preserve reason (last run context) or clear if requested
        }
    }

    suspend fun snapshot(): List<ToolState> = mutex.withLock { tools.values.map { it.copy(items = ArrayDeque(it.items)) } }

    suspend fun toolDetail(toolKey: ToolStateEnum): ToolState? =
        mutex.withLock {
            tools[toolKey]?.copy(items = ArrayDeque(tools[toolKey]!!.items))
        }

    /**
     * Add item to tool's items deque with automatic size limit enforcement.
     * Keeps only last MAX_ITEMS_PER_TOOL items to prevent memory exhaustion.
     */
    private fun addItem(
        toolState: ToolState,
        item: Item,
    ) {
        toolState.items.addLast(item)
        while (toolState.items.size > MAX_ITEMS_PER_TOOL) {
            toolState.items.removeFirst()
        }
    }

    /**
     * Send WebSocket notification to all connected clients about indexing status change.
     */
    private fun notifyClients(toolState: ToolState) {
        kotlin.runCatching {
            val event =
                IndexingStatusUpdateEvent(
                    toolKey = toolState.toolKey,
                    displayName = toolState.displayName,
                    state = toolState.state.name,
                    processed = toolState.processed,
                    errors = toolState.errors,
                    lastError = toolState.lastError,
                    timestamp = Instant.now().toString(),
                )
            webSocketSessionManager.broadcastToChannel(
                json.encodeToString(event),
                WebSocketChannelTypeEnum.NOTIFICATIONS,
            )
        }
    }
}
