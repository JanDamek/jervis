package com.jervis.scheduler

import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.scheduling.support.CronExpression
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Idempotent seeder for the three baseline proactive triggers that were
 * previously hard-coded in Python (`app/proactive/scheduler.py`).
 *
 * On every app start:
 *  1. For each baseline name, `insertIfAbsent` — existing rows are left
 *     untouched so operator edits (different cron, disabled=true, …)
 *     survive restarts.
 *  2. Does NOT touch `nextRunAt` on already-present triggers — that is
 *     the executor's responsibility; overwriting would re-trigger fires
 *     on every pod boot.
 *
 * Cron expressions use Spring's 6-field form (seconds precision).
 * Times are intended to fire at 7:00 / 9:00 / 8:00 Europe/Prague.
 */
@Component
class ProactiveTriggerSeeder(
    private val repository: ScheduledTriggerRepository,
) {
    private val logger = KotlinLogging.logger {}

    @EventListener(ApplicationReadyEvent::class)
    fun onReady() {
        runBlocking { seedBaselineTriggers() }
    }

    private suspend fun seedBaselineTriggers() {
        BASELINE.forEach { baseline ->
            val existing = repository.findById(baseline.name)
            if (existing != null) {
                logger.debug { "seeder | trigger '${baseline.name}' already present — skipping" }
                return@forEach
            }
            val doc = ScheduledTriggerDocument(
                name = baseline.name,
                cronExpression = baseline.cron,
                timezone = PRAGUE,
                handlerRef = baseline.handlerRef,
                payload = baseline.payload,
                nextRunAt = firstFire(baseline.cron),
                enabled = true,
                createdBy = "seeder",
            )
            repository.save(doc)
            logger.info { "seeder | inserted trigger '${baseline.name}' (cron='${baseline.cron}', handler=${baseline.handlerRef})" }
        }
    }

    private fun firstFire(cron: String): Instant {
        val expression = CronExpression.parse(cron)
        val zone = ZoneId.of(PRAGUE)
        val now = LocalDateTime.now(zone)
        val next = expression.next(now) ?: error("cron '$cron' yields no fire time")
        return next.atZone(zone).toInstant()
    }

    private data class Baseline(
        val name: String,
        val cron: String,
        val handlerRef: String,
        val payload: Map<String, String> = emptyMap(),
    )

    companion object {
        private const val PRAGUE = "Europe/Prague"

        private val BASELINE = listOf(
            Baseline(
                name = "morning-briefing",
                // 7:00 Mon-Fri
                cron = "0 0 7 * * MON-FRI",
                handlerRef = "morning-briefing",
            ),
            Baseline(
                name = "overdue-check",
                // 9:00 Mon-Fri
                cron = "0 0 9 * * MON-FRI",
                handlerRef = "overdue-check",
            ),
            Baseline(
                name = "weekly-summary",
                // 8:00 Monday
                cron = "0 0 8 * * MON",
                handlerRef = "weekly-summary",
            ),
        )
    }
}
