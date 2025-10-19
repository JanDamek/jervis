package com.jervis.service.debug

import com.jervis.service.IDebugWindowService
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import mu.KotlinLogging
import org.springframework.stereotype.Service
import java.time.LocalDateTime
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.swing.SwingUtilities

/**
 * Desktop implementation of debug window service for managing LLM debug sessions.
 * Implements 512MB RAM limit and 1-day retention policy to prevent JVM heap issues.
 */
@Service
class DesktopDebugWindowService : IDebugWindowService {
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

    override suspend fun startDebugSession(
        sessionId: String,
        promptType: String,
        systemPrompt: String,
        userPrompt: String,
        clientId: String?,
        clientName: String?,
    ) {
        cleanupOldSessions()

        val session =
            DebugSession(
                id = sessionId,
                promptType = promptType,
                systemPrompt = systemPrompt,
                userPrompt = userPrompt,
                startTime = LocalDateTime.now(),
                responseBuffer = StringBuilder(),
                clientId = clientId,
                clientName = clientName,
            )

        val sessionMemory = estimateSessionMemory(session)

        // Check memory limits
        if (currentMemoryUsage.get() + sessionMemory > maxMemoryBytes) {
            cleanupLargestSessions()
        }

        sessions[sessionId] = session
        currentMemoryUsage.addAndGet(sessionMemory)

        logger.info { "Debug session started: $sessionId ($promptType) for client: ${clientName ?: "System"} - Memory: ${sessionMemory}B" }

        // Show debug window and add session tab
        ensureDebugWindowVisible()
        SwingUtilities.invokeLater {
            debugWindow?.displaySession(session)
        }

        // Emit event
        _debugEvents.tryEmit(
            DebugEvent.SessionStarted(sessionId, promptType, systemPrompt, userPrompt),
        )
    }

    override suspend fun appendResponse(
        sessionId: String,
        chunk: String,
    ) {
        sessions[sessionId]?.let { session ->
            val chunkSize = chunk.toByteArray(Charsets.UTF_8).size.toLong()
            session.responseBuffer.append(chunk)
            currentMemoryUsage.addAndGet(chunkSize)

            // Update UI on Swing thread - pass sessionId to target correct tab
            SwingUtilities.invokeLater {
                debugWindow?.appendResponse(sessionId, chunk)
            }

            // Emit event
            _debugEvents.tryEmit(DebugEvent.ResponseChunk(sessionId, chunk))
        }
    }

    override suspend fun completeSession(sessionId: String) {
        sessions[sessionId]?.let { session ->
            session.complete()
            logger.info { "Debug session completed: $sessionId" }

            _debugEvents.tryEmit(DebugEvent.SessionCompleted(sessionId))

            debugWindow?.completeSession(sessionId)

            cleanupSession(sessionId)
        }
    }

    override fun showDebugWindow() {
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

    override fun hideDebugWindow() {
        SwingUtilities.invokeLater {
            debugWindow?.isVisible = false
        }
    }

    override fun isDebugWindowVisible(): Boolean = debugWindow?.isVisible ?: false

    override fun getActiveSessionIds(): List<String> = sessions.keys.toList()

    private fun ensureDebugWindowVisible() {
        SwingUtilities.invokeLater {
            if (debugWindow == null) {
                debugWindow = DebugWindowFrame()
            }
            debugWindow?.let { window ->
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
