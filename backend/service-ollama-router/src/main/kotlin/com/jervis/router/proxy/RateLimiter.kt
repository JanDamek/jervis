package com.jervis.router.proxy

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import mu.KotlinLogging
import kotlin.math.max

private val logger = KotlinLogging.logger {}

/**
 * Sliding-window rate limiter mirroring Python `SlidingWindowRateLimiter`.
 *
 * Tracks request timestamps (epoch ms) in a deque under a mutex; callers
 * suspend until the oldest entry falls outside the window. Used to throttle
 * OpenRouter cloud calls (FREE / PAID buckets) before issuing the request,
 * eliminating reactive 429s.
 */
class SlidingWindowRateLimiter(
    @Volatile var maxRequests: Int,
    val windowMs: Long = 60_000L,
    val name: String = "",
) {
    private val timestamps: ArrayDeque<Long> = ArrayDeque()
    private val mutex = Mutex()

    @Volatile var totalRequests: Long = 0L
        private set
    @Volatile var totalWaits: Long = 0L
        private set
    @Volatile var totalWaitMs: Long = 0L
        private set

    private fun cleanup(now: Long) {
        val cutoff = now - windowMs
        while (timestamps.isNotEmpty() && timestamps.first() < cutoff) timestamps.removeFirst()
    }

    /**
     * Suspend until a slot is free or [timeoutMs] elapses. Returns true on
     * success, false on timeout (caller should fail the request).
     */
    suspend fun acquire(timeoutMs: Long = windowMs + 5_000L): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        var waited = false
        var waitStart = 0L
        while (true) {
            mutex.withLock {
                val now = System.currentTimeMillis()
                cleanup(now)
                if (timestamps.size < maxRequests) {
                    timestamps.addLast(now)
                    totalRequests += 1
                    if (waited) {
                        val duration = System.currentTimeMillis() - waitStart
                        totalWaits += 1
                        totalWaitMs += duration
                        logger.info {
                            "RATE_LIMIT $name | waited ${duration}ms for slot " +
                                "(${timestamps.size}/$maxRequests used)"
                        }
                    }
                    return true
                }
                val waitMs = (timestamps.first() + windowMs) - now + 50
                val remainingBudget = deadline - now
                if (waitMs > remainingBudget) {
                    logger.warn {
                        "RATE_LIMIT $name | timeout — need ${waitMs}ms but only ${remainingBudget}ms budget"
                    }
                    return false
                }
                if (!waited) {
                    waited = true
                    waitStart = System.currentTimeMillis()
                    logger.info {
                        "RATE_LIMIT $name | at capacity ($maxRequests/$maxRequests), waiting ${waitMs}ms"
                    }
                }
            }
            // sleep outside the lock so other coroutines can probe
            delay(minOf(3_000L, max(50L, deadline - System.currentTimeMillis())))
        }
    }

    suspend fun status(): RateLimitStatus = mutex.withLock {
        val now = System.currentTimeMillis()
        cleanup(now)
        val used = timestamps.size
        val nextSlotMs = if (used >= maxRequests && timestamps.isNotEmpty()) {
            max(0L, timestamps.first() + windowMs - now)
        } else 0L
        RateLimitStatus(
            name = name,
            limit = maxRequests,
            windowMs = windowMs,
            used = used,
            available = max(0, maxRequests - used),
            nextSlotMs = nextSlotMs,
        )
    }
}

data class RateLimitStatus(
    val name: String,
    val limit: Int,
    val windowMs: Long,
    val used: Int,
    val available: Int,
    val nextSlotMs: Long,
)

object OpenRouterRateLimiters {
    val free: SlidingWindowRateLimiter = SlidingWindowRateLimiter(200, 60_000L, "free-models")
    val paid: SlidingWindowRateLimiter = SlidingWindowRateLimiter(200, 60_000L, "paid-models")

    suspend fun acquire(queueName: String, timeoutMs: Long = 65_000L): Boolean = when (queueName) {
        "FREE" -> free.acquire(timeoutMs)
        else -> paid.acquire(timeoutMs)
    }

    fun updateLimits(freeRpm: Int? = null, paidRpm: Int? = null) {
        if (freeRpm != null && freeRpm != free.maxRequests) {
            logger.info { "Updating FREE rate limit: ${free.maxRequests} → $freeRpm RPM" }
            free.maxRequests = freeRpm
        }
        if (paidRpm != null && paidRpm != paid.maxRequests) {
            logger.info { "Updating PAID rate limit: ${paid.maxRequests} → $paidRpm RPM" }
            paid.maxRequests = paidRpm
        }
    }
}
