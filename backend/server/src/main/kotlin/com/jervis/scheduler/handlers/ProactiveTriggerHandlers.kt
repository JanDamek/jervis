package com.jervis.scheduler.handlers

import com.jervis.proactive.ProactiveScheduler
import com.jervis.scheduler.TriggerHandler
import mu.KotlinLogging
import org.springframework.stereotype.Component

/**
 * Spring beans that back the three proactive triggers moved from the
 * Python `app/proactive/scheduler.py` into the Kotlin cron loop.
 *
 * Handlers delegate straight to the existing [ProactiveScheduler]
 * service — the business logic itself has always lived in Kotlin; only
 * the cron trigger moved.
 *
 * Default client id matches the Python fallback (Jervis project owner)
 * so behaviour is identical after migration. Operators can override per
 * trigger by setting `clientId` in `ScheduledTriggerDocument.payload`.
 */
private const val DEFAULT_PROACTIVE_CLIENT_ID = "68a332361b04695a243e5ae8"

private val logger = KotlinLogging.logger {}

private fun Map<String, String>.clientId(): String =
    this["clientId"].takeUnless { it.isNullOrBlank() } ?: DEFAULT_PROACTIVE_CLIENT_ID

@Component
class MorningBriefingHandler(
    private val proactiveScheduler: ProactiveScheduler,
) : TriggerHandler {
    override val name: String = "morning-briefing"

    override suspend fun execute(payload: Map<String, String>) {
        val cid = payload.clientId()
        val result = proactiveScheduler.generateMorningBriefing(cid)
        logger.info { "trigger:morning-briefing | client=$cid → ${result.take(120)}" }
    }
}

@Component
class OverdueCheckHandler(
    private val proactiveScheduler: ProactiveScheduler,
) : TriggerHandler {
    override val name: String = "overdue-check"

    override suspend fun execute(payload: Map<String, String>) {
        val count = proactiveScheduler.checkOverdueInvoices()
        logger.info { "trigger:overdue-check | overdue=$count" }
    }
}

@Component
class WeeklySummaryHandler(
    private val proactiveScheduler: ProactiveScheduler,
) : TriggerHandler {
    override val name: String = "weekly-summary"

    override suspend fun execute(payload: Map<String, String>) {
        val cid = payload.clientId()
        val result = proactiveScheduler.generateWeeklySummary(cid)
        logger.info { "trigger:weekly-summary | client=$cid → ${result.take(120)}" }
    }
}
