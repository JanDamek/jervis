package com.jervis.scheduler

import mu.KotlinLogging
import org.springframework.stereotype.Component

/**
 * Resolves [TriggerHandler] beans by [TriggerHandler.name]. Spring
 * auto-injects every handler bean in the context; the executor queries
 * by `handlerRef` from each scheduled trigger.
 *
 * Duplicate names fail the registry boot — two handlers claiming the
 * same key would make trigger routing non-deterministic, which would
 * silently corrupt production scheduling.
 */
@Component
class TriggerHandlerRegistry(handlers: List<TriggerHandler>) {
    private val logger = KotlinLogging.logger {}
    private val byName: Map<String, TriggerHandler>

    init {
        val grouped = handlers.groupBy { it.name }
        val duplicates = grouped.filter { (_, xs) -> xs.size > 1 }
        if (duplicates.isNotEmpty()) {
            val summary = duplicates.entries.joinToString("; ") { (n, xs) ->
                "$n -> ${xs.joinToString { it::class.qualifiedName ?: "?" }}"
            }
            error("TriggerHandler name collision — every handler must have a unique name: $summary")
        }
        byName = grouped.mapValues { it.value.single() }
        logger.info { "TriggerHandlerRegistry | registered ${byName.size} handlers: ${byName.keys}" }
    }

    fun resolve(handlerRef: String): TriggerHandler? = byName[handlerRef]
}
