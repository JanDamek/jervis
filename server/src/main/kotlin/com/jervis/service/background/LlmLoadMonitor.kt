package com.jervis.service.background

import mu.KotlinLogging
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

/**
 * Monitors LLM load and provides idle detection for background task scheduling.
 *
 * Tracks active foreground LLM requests and determines when the system is idle
 * enough to run background cognitive tasks.
 */
@Component
class LlmLoadMonitor {
    private val logger = KotlinLogging.logger {}
    private val activeForegroundRequests = AtomicInteger(0)

    @Volatile
    private var lastActivityTimestamp = Instant.now()

    /**
     * Registers the start of a foreground LLM request.
     * If this transitions from idle to busy, triggers background interrupt.
     */
    fun registerRequestStart() {
        val previous = activeForegroundRequests.getAndIncrement()
        logger.debug { "LLM request started: active=${activeForegroundRequests.get()}" }

        if (previous == 0) {
            logger.info { "Transitioning from IDLE to BUSY - interrupting background tasks" }
            BackgroundEngine.interruptNow()
        }
    }

    /**
     * Registers the completion of a foreground LLM request.
     * Updates last activity timestamp for idle detection.
     */
    fun registerRequestEnd() {
        val current = activeForegroundRequests.decrementAndGet()
        lastActivityTimestamp = Instant.now()
        logger.debug { "LLM request completed: active=$current" }
    }

    /**
     * Checks if the system has been idle for at least the specified threshold.
     *
     * Idle means:
     * - No active foreground requests (activeForegroundRequests == 0)
     * - Duration since last activity >= threshold
     */
    fun isIdleFor(threshold: Duration): Boolean {
        val isNoActiveRequests = activeForegroundRequests.get() == 0
        val idleDuration = Duration.between(lastActivityTimestamp, Instant.now())
        return isNoActiveRequests && idleDuration >= threshold
    }

    /**
     * Returns the number of currently active foreground requests.
     */
    fun getActiveRequestCount(): Int = activeForegroundRequests.get()

    /**
     * Returns the duration since last activity.
     */
    fun getIdleDuration(): Duration = Duration.between(lastActivityTimestamp, Instant.now())
}
