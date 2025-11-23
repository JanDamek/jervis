package com.jervis.service.indexing

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import mu.KotlinLogging
import kotlin.coroutines.coroutineContext

/**
 * Generic periodic polling pattern for resources that don't have "NEW" state tracking.
 * Examples: Git repos, external APIs, file systems.
 *
 * Pattern:
 * 1. While active: poll for accounts/connections
 * 2. For each account: check if polling is needed (based on last poll time + interval)
 * 3. If needed: execute polling logic
 * 4. Update last poll timestamp
 * 5. Delay before next cycle
 *
 * Subclasses configure:
 * - pollingIntervalMs: how often to poll each account (from properties)
 * - initialDelayMs: startup delay before first poll
 * - cycleDelayMs: delay between checking all accounts
 */
abstract class AbstractPeriodicPoller<A> {
    private val logger = KotlinLogging.logger {}

    /** Polling interval per account in milliseconds (from properties). */
    protected abstract val pollingIntervalMs: Long

    /** Initial delay before first poll starts. */
    protected open val initialDelayMs: Long = 60_000L // 1 minute

    /** Delay between checking all accounts for polling need. */
    protected open val cycleDelayMs: Long = 30_000L // 30 seconds

    /** For logs/tracing only. */
    protected abstract val pollerName: String

    // --- Domain-specific hooks ---

    /** Stream of all accounts/connections to poll. */
    protected abstract fun accountsFlow(): Flow<A>

    /** Get last poll timestamp for account. Return null if never polled. */
    protected abstract suspend fun getLastPollTime(account: A): Long?

    /** Execute polling logic for account. Return true if successful. */
    protected abstract suspend fun executePoll(account: A): Boolean

    /** Update last poll timestamp after successful poll. */
    protected abstract suspend fun updateLastPollTime(account: A, timestamp: Long)

    /** Human-readable account identifier for logs. */
    protected abstract fun accountLogLabel(account: A): String

    /**
     * Start continuous polling loop.
     * Runs until coroutine is cancelled.
     */
    suspend fun startPeriodicPolling() {
        logger.info { "Starting $pollerName with interval ${pollingIntervalMs}ms, initial delay ${initialDelayMs}ms" }

        // Initial delay
        if (initialDelayMs > 0) {
            logger.info { "[$pollerName] Waiting ${initialDelayMs}ms before first poll..." }
        }
        delay(initialDelayMs)

        logger.info { "[$pollerName] Starting polling loop, checking accounts every ${cycleDelayMs}ms" }

        while (coroutineContext.isActive) {
            try {
                pollAllAccounts()
            } catch (e: Exception) {
                logger.error(e) { "[$pollerName] Error during polling cycle" }
            }

            // Wait before next cycle
            delay(cycleDelayMs)
        }
    }

    private suspend fun pollAllAccounts() {
        var accountCount = 0
        var polledCount = 0
        var skippedCount = 0

        accountsFlow().collect { account ->
            accountCount++
            try {
                val label = accountLogLabel(account)
                val now = System.currentTimeMillis()
                val lastPoll = getLastPollTime(account)

                val shouldPoll = if (lastPoll == null) {
                    logger.info { "[$pollerName] First poll for $label" }
                    true
                } else {
                    val elapsed = now - lastPoll
                    if (elapsed >= pollingIntervalMs) {
                        logger.info { "[$pollerName] Polling $label (last poll: ${elapsed}ms ago)" }
                        true
                    } else {
                        logger.debug { "[$pollerName] Skipping $label (next poll in ${pollingIntervalMs - elapsed}ms)" }
                        skippedCount++
                        false
                    }
                }

                if (shouldPoll) {
                    val success = executePoll(account)
                    if (success) {
                        updateLastPollTime(account, now)
                        logger.info { "[$pollerName] Completed poll for $label" }
                        polledCount++
                    } else {
                        logger.warn { "[$pollerName] Poll failed for $label" }
                    }
                }
            } catch (e: Exception) {
                logger.error(e) { "[$pollerName] Error polling ${accountLogLabel(account)}" }
            }
        }

        if (accountCount == 0) {
            logger.info { "[$pollerName] No accounts found to poll" }
        } else {
            logger.debug { "[$pollerName] Checked $accountCount accounts: polled=$polledCount, skipped=$skippedCount" }
        }
    }
}
