package com.jervis.service.debug

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import mu.KotlinLogging
import org.springframework.stereotype.Service
import java.time.LocalDateTime
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.swing.SwingUtilities

/**
 * Service for managing debug sessions for LLM calls with memory management.
 * Implements 512MB RAM limit and 1-day retention policy to prevent JVM heap issues.
 */
@Service
class DesktopDebugWindowService {
    private val logger = KotlinLogging.logger {}

    // Memory management
    private val maxMemoryBytes = 512L * 1024 * 1024 // 512MB
    private val currentMemoryUsage = AtomicLong(0)
    private val maxRetentionHours = 24L

    // Active sessions storage
    private val sessions = ConcurrentHashMap<String, DebugSession>()

    // Event flow for UI updates
    private val _debugEvents = MutableSharedFlow<DebugEvent>(replay = 0)
    val debugEvents: SharedFlow<DebugEvent> = _debugEvents

    // Debug window reference
    @Volatile
    private var debugWindow: DebugWindowFrame? = null

    suspend fun startDebugSession(
        sessionId: String,
        promptType: String,
        systemPrompt: String,
        userPrompt: String,
    ): DebugSession {
        cleanupOldSessions()

        val session =
            DebugSession(
                id = sessionId,
                promptType = promptType,
                systemPrompt = systemPrompt,
                userPrompt = userPrompt,
                startTime = LocalDateTime.now(),
                responseBuffer = StringBuilder(),
            )

        val sessionMemory = estimateSessionMemory(session)

        // Check memory limits
        if (currentMemoryUsage.get() + sessionMemory > maxMemoryBytes) {
            cleanupLargestSessions()
        }

        sessions[sessionId] = session
        currentMemoryUsage.addAndGet(sessionMemory)

        logger.info { "Debug session started: $sessionId ($promptType) - Memory: ${sessionMemory}B" }

        // Show debug window if not visible
        ensureDebugWindowVisible(session)

        // Emit event
        _debugEvents.tryEmit(
            DebugEvent.SessionStarted(sessionId, promptType, systemPrompt, userPrompt),
        )

        return session
    }

    suspend fun appendResponse(
        sessionId: String,
        chunk: String,
    ) {
        sessions[sessionId]?.let { session ->
            val chunkSize = chunk.toByteArray(Charsets.UTF_8).size.toLong()
            session.responseBuffer.append(chunk)
            currentMemoryUsage.addAndGet(chunkSize)

            // Update UI on Swing thread
            SwingUtilities.invokeLater {
                debugWindow?.appendResponse(chunk)
            }

            // Emit event
            _debugEvents.tryEmit(DebugEvent.ResponseChunk(sessionId, chunk))
        }
    }

    suspend fun completeSession(
        sessionId: String,
        finalResponse: com.jervis.domain.llm.LlmResponse,
    ) {
        sessions[sessionId]?.let { session ->
            session.complete(finalResponse)
            logger.info { "Debug session completed: $sessionId" }

            // Emit event
            _debugEvents.tryEmit(DebugEvent.SessionCompleted(sessionId, finalResponse))

            // Clean up after completion to free memory
            cleanupSession(sessionId)
        }
    }

    fun showDebugWindow() {
        SwingUtilities.invokeLater {
            if (debugWindow == null) {
                debugWindow = DebugWindowFrame()
            }
            debugWindow?.let { window ->
                if (!window.isVisible) {
                    window.isVisible = true
                    window.toFront()
                    window.requestFocus()
                }
            }
        }
    }

    fun hideDebugWindow() {
        SwingUtilities.invokeLater {
            debugWindow?.isVisible = false
        }
    }

    fun isDebugWindowVisible(): Boolean = debugWindow?.isVisible ?: false

    fun getActiveSessionIds(): List<String> = sessions.keys.toList()

    private fun ensureDebugWindowVisible(session: DebugSession) {
        SwingUtilities.invokeLater {
            if (debugWindow == null) {
                debugWindow = DebugWindowFrame()
            }
            debugWindow?.let { window ->
                window.displaySession(session)
                if (!window.isVisible) {
                    window.isVisible = true
                    window.toFront()
                }
            }
        }
    }

    private fun cleanupOldSessions() {
        val cutoffTime = LocalDateTime.now().minusHours(maxRetentionHours)
        val sessionsToRemove =
            sessions.values
                .filter { it.startTime.isBefore(cutoffTime) }
                .map { it.id }

        sessionsToRemove.forEach { sessionId ->
            cleanupSession(sessionId)
        }

        if (sessionsToRemove.isNotEmpty()) {
            logger.info { "Cleaned up ${sessionsToRemove.size} old debug sessions" }
        }
    }

    private fun cleanupLargestSessions() {
        val sortedSessions =
            sessions.values
                .sortedByDescending { estimateSessionMemory(it) }

        var freedMemory = 0L
        val sessionsToRemove = mutableListOf<String>()

        for (session in sortedSessions) {
            if (currentMemoryUsage.get() - freedMemory < maxMemoryBytes * 0.8) break

            val sessionMemory = estimateSessionMemory(session)
            freedMemory += sessionMemory
            sessionsToRemove.add(session.id)
        }

        sessionsToRemove.forEach { sessionId ->
            cleanupSession(sessionId)
        }

        logger.info { "Memory cleanup: freed ${freedMemory}B by removing ${sessionsToRemove.size} sessions" }
    }

    private fun cleanupSession(sessionId: String) {
        sessions[sessionId]?.let { session ->
            val sessionMemory = estimateSessionMemory(session)
            sessions.remove(sessionId)
            currentMemoryUsage.addAndGet(-sessionMemory)
        }
    }

    private fun estimateSessionMemory(session: DebugSession): Long {
        return (
            session.systemPrompt.length +
                session.userPrompt.length +
                session.responseBuffer.length
        ) * 2L // UTF-8 estimation
    }
}

data class DebugSession(
    val id: String,
    val promptType: String,
    val systemPrompt: String,
    val userPrompt: String,
    val startTime: LocalDateTime,
    val responseBuffer: StringBuilder,
    var completionTime: LocalDateTime? = null,
    var finalResponse: com.jervis.domain.llm.LlmResponse? = null,
) {
    fun complete(response: com.jervis.domain.llm.LlmResponse) {
        completionTime = LocalDateTime.now()
        finalResponse = response
    }

    fun isCompleted(): Boolean = completionTime != null
}

sealed class DebugEvent {
    data class SessionStarted(
        val sessionId: String,
        val promptType: String,
        val systemPrompt: String,
        val userPrompt: String,
    ) : DebugEvent()

    data class ResponseChunk(
        val sessionId: String,
        val chunk: String,
    ) : DebugEvent()

    data class SessionCompleted(
        val sessionId: String,
        val response: com.jervis.domain.llm.LlmResponse,
    ) : DebugEvent()
}
