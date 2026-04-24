package com.jervis.scheduler

import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.index.Indexed
import org.springframework.data.mongodb.core.mapping.Document
import java.time.Instant

/**
 * Cron-scheduled trigger that the Kotlin [ScheduledTriggerExecutor] fires.
 * Replaces the Python `proactive_scheduler` — same cadence, same handlers,
 * but the cron loop now lives in the orchestrator's own pod (single Kotlin
 * replica, no leader election).
 *
 * Design:
 *  - Each document names a Spring bean via [handlerRef]; the executor
 *    looks the bean up through [TriggerHandlerRegistry] and calls
 *    `execute(payload)` at each fire time.
 *  - [nextRunAt] is advanced by the executor using [cronExpression] — it
 *    is the single source of truth for "should I fire this?". The cron
 *    expression itself is re-parsed on every tick so operators can edit
 *    triggers at runtime (new cron takes effect next cycle).
 *  - Catch-up semantics: if the server was down across the cron boundary,
 *    the executor fires once on boot and advances `nextRunAt` past now.
 *    The spec explicitly rejects per-run audit (`lastRunAt`, `lastError`,
 *    …) — Claude keeps any history it needs in scratchpad / Thought Map.
 *
 * Single-replica deployment is assumed. If the Kotlin server is ever
 * horizontally scaled, this entity will need an owner lease column before
 * it's safe to re-enable.
 */
@Document(collection = "scheduled_triggers")
data class ScheduledTriggerDocument(
    @Id
    val name: String,
    val cronExpression: String,
    val timezone: String = "Europe/Prague",
    val handlerRef: String,
    /** Arbitrary key/value payload handed to the handler at execution time. */
    val payload: Map<String, String> = emptyMap(),
    /** When the executor should next fire. Populated on insert from
     *  `CronExpression.next(now)` and advanced after each fire. */
    @Indexed
    val nextRunAt: Instant,
    val enabled: Boolean = true,
    /** Free-form source — "seeder", "user:<uid>", "mcp:dispatch". */
    val createdBy: String = "unknown",
    val createdAt: Instant = Instant.now(),
)
