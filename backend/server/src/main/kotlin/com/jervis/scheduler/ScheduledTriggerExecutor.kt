package com.jervis.scheduler

import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import org.springframework.scheduling.support.CronExpression
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Reads [ScheduledTriggerDocument]s from Mongo, fires the ones whose
 * `nextRunAt` is in the past, and advances `nextRunAt` via their
 * [cronExpression]. One tick every [TICK_INTERVAL_MS] ms.
 *
 * Catch-up policy:
 *  - If the server was down across a cron boundary, the trigger fires
 *    exactly ONCE (not once per missed boundary) on the next tick, then
 *    its `nextRunAt` jumps to the first boundary AFTER `now`. Matches
 *    the semantics the spec chose: operators don't want a flood after
 *    pod restart.
 *  - If the cron expression itself won't parse or yields no future
 *    boundary, the trigger is disabled by bumping `nextRunAt` to
 *    `Instant.MAX` and logging at ERROR so operators notice.
 *
 * Persistence:
 *  - The executor writes only `nextRunAt` on advance — no run audit, no
 *    lastError column. Handlers that need history use their own scope
 *    (scratchpad / Thought Map / agent job record) to keep it.
 */
@Component
class ScheduledTriggerExecutor(
    private val repository: ScheduledTriggerRepository,
    private val registry: TriggerHandlerRegistry,
) {
    private val logger = KotlinLogging.logger {}
    private val supervisor = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Default + supervisor)

    @Volatile
    private var loopJob: kotlinx.coroutines.Job? = null

    @PostConstruct
    fun start() {
        loopJob = scope.launch { tickLoop() }
        logger.info { "ScheduledTriggerExecutor started (tick=${TICK_INTERVAL_MS}ms)" }
    }

    @PreDestroy
    fun stop() {
        logger.info { "ScheduledTriggerExecutor stopping" }
        runBlocking { runCatching { loopJob?.cancelAndJoin() } }
        scope.cancel()
    }

    private suspend fun tickLoop() {
        // Initial delay so Spring finishes context wiring (repositories,
        // handlers) before we start firing.
        delay(INITIAL_DELAY_MS)
        while (scope.isActive) {
            runCatching { fireDueTriggers(Instant.now()) }
                .onFailure { logger.warn(it) { "scheduler tick failed (continuing)" } }
            delay(TICK_INTERVAL_MS)
        }
    }

    internal suspend fun fireDueTriggers(now: Instant) {
        val due = repository
            .findByEnabledIsTrueAndNextRunAtLessThanEqualOrderByNextRunAtAsc(now)
            .toList()
        if (due.isEmpty()) return
        logger.debug { "scheduler | ${due.size} triggers due at $now" }

        for (trigger in due) {
            val handler = registry.resolve(trigger.handlerRef)
            if (handler == null) {
                logger.warn {
                    "scheduler | trigger '${trigger.name}' references unknown handler " +
                        "'${trigger.handlerRef}' — skipping and advancing nextRunAt"
                }
            } else {
                runCatching { handler.execute(trigger.payload) }
                    .onFailure { e ->
                        logger.warn(e) { "scheduler | handler '${handler.name}' failed for trigger '${trigger.name}'" }
                    }
            }

            val next = computeNextRun(trigger, now)
            repository.save(trigger.copy(nextRunAt = next))
        }
    }

    /**
     * Pick the first cron boundary strictly after [after]. On a broken
     * cron expression, jump to [Instant.MAX] so the trigger stops firing
     * but stays in the DB for the operator to edit.
     */
    internal fun computeNextRun(
        trigger: ScheduledTriggerDocument,
        after: Instant,
    ): Instant {
        val cron = runCatching { CronExpression.parse(trigger.cronExpression) }
            .onFailure {
                logger.error(it) { "scheduler | invalid cronExpression on trigger '${trigger.name}' — disabling advance" }
            }
            .getOrNull() ?: return Instant.MAX

        val zone = runCatching { ZoneId.of(trigger.timezone) }.getOrDefault(ZoneId.of("UTC"))
        val afterLocal = LocalDateTime.ofInstant(after, zone)
        val nextLocal = cron.next(afterLocal)
        if (nextLocal == null) {
            logger.error { "scheduler | cronExpression '${trigger.cronExpression}' yields no future fire — disabling '${trigger.name}'" }
            return Instant.MAX
        }
        return nextLocal.atZone(zone).toInstant()
    }

    companion object {
        private const val INITIAL_DELAY_MS = 10_000L
        private const val TICK_INTERVAL_MS = 60_000L
    }
}
