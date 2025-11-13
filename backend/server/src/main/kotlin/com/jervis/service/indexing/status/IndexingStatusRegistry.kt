package com.jervis.service.indexing.status

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.springframework.stereotype.Service
import java.time.Instant

/**
 * Runtime in-memory registry tracking indexing status across all tools.
 * Not persisted. Keeps current run and last run summary with recent items log per tool.
 */
@Service
class IndexingStatusRegistry {
    data class ToolState(
        val toolKey: String,
        var displayName: String,
        var state: State = State.IDLE,
        var runningSince: Instant? = null,
        var processed: Int = 0,
        var errors: Int = 0,
        var lastError: String? = null,
        var lastRunStartedAt: Instant? = null,
        var lastRunFinishedAt: Instant? = null,
        /** Short human readable reason/context for current run (or last run) */
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
    )

    private val mutex = Mutex()
    private val tools = linkedMapOf<String, ToolState>()

    suspend fun ensureTool(
        toolKey: String,
        displayName: String = toolKey,
    ): ToolState =
        mutex.withLock {
            tools.getOrPut(toolKey) { ToolState(toolKey, displayName) }
        }

    suspend fun start(
        toolKey: String,
        displayName: String = toolKey,
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
            t.items.clear()
            t.reason = message // use start message as short reason/context
            message?.let { t.items.add(Item(Instant.now(), "INFO", it)) }
        }
    }

    suspend fun progress(
        toolKey: String,
        processedInc: Int = 1,
        message: String? = null,
    ) {
        mutex.withLock {
            val t = tools[toolKey] ?: return
            t.processed += processedInc
            val msg = message ?: "Processed +$processedInc"
            t.items.add(Item(Instant.now(), "PROGRESS", msg, processedDelta = processedInc))
        }
    }

    suspend fun info(
        toolKey: String,
        message: String,
    ) {
        mutex.withLock {
            val t = tools[toolKey] ?: return
            t.items.add(Item(Instant.now(), "INFO", message))
            t.reason = message // update reason when an info is sent
        }
    }

    suspend fun error(
        toolKey: String,
        message: String,
    ) {
        mutex.withLock {
            val t = tools[toolKey] ?: return
            t.errors += 1
            t.lastError = message.take(500)
            t.items.add(Item(Instant.now(), "ERROR", message, errorsDelta = 1))
        }
    }

    suspend fun finish(
        toolKey: String,
        message: String? = null,
    ) {
        mutex.withLock {
            val t = tools[toolKey] ?: return
            t.lastRunFinishedAt = Instant.now()
            t.state = State.IDLE
            t.runningSince = null
            message?.let { t.items.add(Item(Instant.now(), "INFO", it)) }
            // preserve reason (last run context) or clear if requested
        }
    }

    suspend fun snapshot(): List<ToolState> = mutex.withLock { tools.values.map { it.copy(items = ArrayDeque(it.items)) } }

    suspend fun toolDetail(toolKey: String): ToolState? =
        mutex.withLock {
            tools[toolKey]?.copy(items = ArrayDeque(tools[toolKey]!!.items))
        }
}
