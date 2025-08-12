package com.jervis.service.llm

import com.jervis.service.setting.SettingService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit

/**
 * Tracks token usage over time and ensures we don't exceed the rate limit.
 * This is used to prevent 429 Too Many Requests errors from the Anthropic API.
 * Handles both input and output token rate limits separately.
 */
@Component
class TokenRateLimiter(
    private val settingService: SettingService,
) {
    private val logger = LoggerFactory.getLogger(TokenRateLimiter::class.java)

    // Enum to distinguish between input and output tokens
    enum class TokenType {
        INPUT,
        OUTPUT,
    }

    // Separate queues to track input and output token usage with timestamps
    private val inputTokenUsage = ConcurrentLinkedQueue<TokenUsage>()
    private val outputTokenUsage = ConcurrentLinkedQueue<TokenUsage>()

    /**
     * Checks if a request with the given input token count exceeds the rate limit.
     * If it would, this method will block until the request can be made.
     *
     * @param tokenCount The number of input tokens in the request
     * @return true if the request can proceed, false if it would exceed the rate limit
     */
    @Synchronized
    fun checkAndWaitForInput(tokenCount: Int): Boolean = checkAndWait(tokenCount, TokenType.INPUT)

    /**
     * Checks if a response with the given output token count exceeds the rate limit.
     * If it would, this method will block until the response can be processed.
     *
     * @param tokenCount The number of output tokens in the response
     * @return true if the response can proceed, false if it would exceed the rate limit
     */
    @Synchronized
    fun checkAndWaitForOutput(tokenCount: Int): Boolean = checkAndWait(tokenCount, TokenType.OUTPUT)

    /**
     * Records the actual output token usage after a response is received.
     * This is important because we can only estimate output tokens before making the request.
     *
     * @param tokenCount The actual number of output tokens used
     */
    @Synchronized
    fun recordOutputTokenUsage(tokenCount: Int) {
        outputTokenUsage.add(TokenUsage(tokenCount, Instant.now().toEpochMilli()))
    }

    /**
     * Checks if a request/response with the given token count exceeds the rate limit.
     * If it would, this method will return false immediately to allow fallback to OpenAI.
     *
     * @param tokenCount The number of tokens
     * @param tokenType The type of tokens (input or output)
     * @return true if the operation can proceed, false if it would exceed the rate limit
     */
    private fun checkAndWait(
        tokenCount: Int,
        tokenType: TokenType,
    ): Boolean {
        // Get the appropriate rate limit from settings
        val rateLimit = getRateLimit(tokenType)
        val rateLimitWindow = settingService.anthropicRateLimitWindowSeconds

        // If rate limiting is disabled, allow the request
        if (rateLimit <= 0) {
            return true
        }

        // Get the appropriate token usage queue
        val tokenUsage = getTokenUsageQueue(tokenType)

        // Clean up old token usage records
        cleanupOldRecords(tokenUsage, rateLimitWindow)

        // Calculate current token usage in the window
        val currentUsage = getCurrentUsage(tokenUsage)

        // Calculate the percentage of the rate limit that would be used
        val percentageUsed = ((currentUsage + tokenCount).toFloat() / rateLimit.toFloat()) * 100

        // Log the current usage and percentage
        logger.debug("Current ${tokenType.name} token usage: $currentUsage/$rateLimit (${percentageUsed.toInt()}%)")

        // If we're within the limit, record usage and return true
        if (currentUsage + tokenCount <= rateLimit) {
            recordTokenUsage(tokenUsage, tokenCount)
            return true
        }

        // We're over the limit - handle, according to settings
        return handleRateLimitExceeded(
            tokenType,
            tokenUsage,
            tokenCount,
            currentUsage,
            rateLimit,
            rateLimitWindow,
        )
    }

    /**
     * Gets the appropriate rate limit for the token type
     */
    private fun getRateLimit(tokenType: TokenType): Int =
        when (tokenType) {
            TokenType.INPUT -> settingService.anthropicRateLimitInputTokens
            TokenType.OUTPUT -> settingService.anthropicRateLimitOutputTokens
        }

    /**
     * Gets the appropriate token usage queue for the token type
     */
    private fun getTokenUsageQueue(tokenType: TokenType): ConcurrentLinkedQueue<TokenUsage> =
        when (tokenType) {
            TokenType.INPUT -> inputTokenUsage
            TokenType.OUTPUT -> outputTokenUsage
        }

    /**
     * Records token usage in the queue
     */
    private fun recordTokenUsage(
        tokenUsage: ConcurrentLinkedQueue<TokenUsage>,
        tokenCount: Int,
    ) {
        tokenUsage.add(TokenUsage(tokenCount, Instant.now().toEpochMilli()))
    }

    /**
     * Handles the case when the rate limit is exceeded
     * Implements a more intelligent approach to handling rate limit exceeded situations
     */
    private fun handleRateLimitExceeded(
        tokenType: TokenType,
        tokenUsage: ConcurrentLinkedQueue<TokenUsage>,
        tokenCount: Int,
        currentUsage: Int,
        rateLimit: Int,
        rateLimitWindow: Int,
    ): Boolean {
        // Calculate how much we're over the limit
        val overLimitAmount = currentUsage + tokenCount - rateLimit
        val overLimitPercentage = (overLimitAmount.toFloat() / rateLimit.toFloat()) * 100

        // If we're only slightly over the limit (less than 10%), try to wait a short time
        // This helps with small bursts of activity without immediately falling back
        if (overLimitPercentage < 10) {
            logger.info(
                "Rate limit slightly exceeded for ${tokenType.name} tokens (${overLimitPercentage.toInt()}% over). Attempting short wait.",
            )
            try {
                // Wait a short time (10% of the window) to see if some tokens expire
                Thread.sleep((rateLimitWindow * 100).toLong())

                // Clean up old records again after waiting
                cleanupOldRecords(tokenUsage, rateLimitWindow)
                val newCurrentUsage = getCurrentUsage(tokenUsage)

                // Check if we're now under the limit
                if (newCurrentUsage + tokenCount <= rateLimit) {
                    recordTokenUsage(tokenUsage, tokenCount)
                    return true
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }

        // Otherwise, try to wait until the rate limit window expires
        return tryWaitForRateLimit(tokenType, tokenUsage, tokenCount, currentUsage, rateLimit, rateLimitWindow)
    }

    /**
     * Tries to wait until the rate limit window expires
     */
    private fun tryWaitForRateLimit(
        tokenType: TokenType,
        tokenUsage: ConcurrentLinkedQueue<TokenUsage>,
        tokenCount: Int,
        currentUsage: Int,
        rateLimit: Int,
        rateLimitWindow: Int,
    ): Boolean {
        val oldestTimestamp = getOldestTimestamp(tokenUsage) ?: return false

        val windowEndTime = oldestTimestamp + TimeUnit.SECONDS.toMillis(rateLimitWindow.toLong())
        val waitTime = windowEndTime - Instant.now().toEpochMilli()

        if (waitTime <= 0) {
            // No need to wait, clean up and try again
            cleanupOldRecords(tokenUsage, rateLimitWindow)
            recordTokenUsage(tokenUsage, tokenCount)
            return true
        }

        try {
            // Log that we're waiting for the rate limit to expire
            logger.info(
                "Rate limit exceeded for ${tokenType.name} tokens. Current usage: $currentUsage, Limit: $rateLimit. Waiting for ${waitTime / 1000.0} seconds until rate limit window expires.",
            )

            // Wait until we can make the request
            Thread.sleep(waitTime)

            // Log that we've finished waiting
            logger.info("Finished waiting for ${tokenType.name} token rate limit. Cleaning up old records.")

            // After waiting, clean up old records again
            cleanupOldRecords(tokenUsage, rateLimitWindow)
            recordTokenUsage(tokenUsage, tokenCount)
            return true
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return false
        }
    }

    /**
     * Cleans up token usage records that are older than the rate limit window.
     * Ensures records are cleaned up exactly after the window + 1 second to guarantee precise rate limiting.
     * Also performs proactive cleanup to maintain a sliding window approach.
     */
    private fun cleanupOldRecords(
        tokenUsage: ConcurrentLinkedQueue<TokenUsage>,
        windowSeconds: Int,
    ) {
        // Calculated cutoff time as exactly a window + 1 second ago to ensure precise reset
        val cutoffTime = Instant.now().minusSeconds(windowSeconds.toLong() + 1).toEpochMilli()

        val initialSize = tokenUsage.size
        while (tokenUsage.isNotEmpty() && tokenUsage.peek().timestamp < cutoffTime) {
            val removed = tokenUsage.poll()
            logger.debug(
                "Removed token usage record: {} tokens from {}. Age: {} seconds",
                removed.tokenCount,
                Instant.ofEpochMilli(removed.timestamp),
                (Instant.now().toEpochMilli() - removed.timestamp) / 1000.0,
            )
        }

        val removedCount = initialSize - tokenUsage.size
        if (removedCount > 0) {
            logger.debug("Cleaned up $removedCount token usage records. Current queue size: ${tokenUsage.size}")
        }
    }

    /**
     * Gets the current token usage within the rate limit window.
     */
    private fun getCurrentUsage(tokenUsage: ConcurrentLinkedQueue<TokenUsage>): Int = tokenUsage.sumOf { it.tokenCount }

    /**
     * Gets the timestamp of the oldest token usage record.
     */
    private fun getOldestTimestamp(tokenUsage: ConcurrentLinkedQueue<TokenUsage>): Long? =
        tokenUsage.minByOrNull { it.timestamp }?.timestamp

    /**
     * Represents a token usage record with a timestamp.
     */
    private data class TokenUsage(
        val tokenCount: Int,
        val timestamp: Long,
    )
}
