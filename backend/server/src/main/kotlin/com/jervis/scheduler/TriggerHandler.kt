package com.jervis.scheduler

/**
 * Spring bean that implements a scheduled trigger's business logic.
 *
 * Each handler advertises its [name], which matches
 * [ScheduledTriggerDocument.handlerRef]. The executor looks the bean up
 * via [TriggerHandlerRegistry] before firing. Handlers are invoked
 * sequentially in one coroutine — if a fast handler is blocked behind a
 * slow one, split the cron to stagger or lift the slow handler onto an
 * agent job (dispatched via AgentJobDispatcher) instead of blocking the
 * scheduler loop.
 */
interface TriggerHandler {
    /**
     * Stable identifier used as [ScheduledTriggerDocument.handlerRef].
     * Conventionally kebab-case and matches the Python trigger endpoint
     * name where applicable, so operators recognise it.
     */
    val name: String

    /**
     * Run the trigger. Throwing aborts this tick's execution of THIS
     * handler only — the executor logs the failure and still advances
     * `nextRunAt` (no retry). Long-running work must be dispatched
     * asynchronously (e.g. an agent job) rather than awaited here.
     */
    suspend fun execute(payload: Map<String, String>)
}
