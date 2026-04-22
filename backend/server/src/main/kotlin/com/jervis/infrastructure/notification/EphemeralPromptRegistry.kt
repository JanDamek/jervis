package com.jervis.infrastructure.notification

import mu.KotlinLogging
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory registry of **ephemeral prompts** — transient Q&A pass-throughs
 * between pod agents (O365 / WhatsApp / meeting bot) and the user that
 * deliberately bypass the TaskDocument lifecycle.
 *
 * See `docs/architecture.md` §ephemeral-prompts for the rationale. A task
 * makes sense when Jervis owns the work (context, priority, history,
 * follow-ups). A prompt is just "ask the user, forward the answer back" —
 * no history, no KB ingest, no qualifier, no sidebar entry.
 *
 * Lifetime:
 *  - created by `ServerO365SessionGrpcImpl` / `ServerWhatsappSessionGrpcImpl`
 *    when the pod asks for user approval off-hours
 *  - resolved by `UserTaskRpcImpl.answerEphemeralPrompt` which reads the
 *    entry, dispatches the corresponding pod action, then removes it
 *  - auto-expired after [TTL] so a stale prompt never blocks cleanup
 *
 * Survives server restart? No — that's fine. The pod emits a fresh prompt
 * on the next tick of its own state machine (AWAITING_MFA / off-hours
 * idle), the same way MFA pushes self-heal after a pod restart.
 */
@Component
class EphemeralPromptRegistry {
    private val logger = KotlinLogging.logger {}

    data class Entry(
        val id: String,
        val kind: String,
        val clientId: String,
        val connectionId: String?,
        val meetingId: String?,
        val createdAt: Instant,
    )

    private val entries: MutableMap<String, Entry> = ConcurrentHashMap()

    fun register(
        kind: String,
        clientId: String,
        connectionId: String? = null,
        meetingId: String? = null,
    ): Entry {
        purgeExpired()
        val id = UUID.randomUUID().toString()
        val entry = Entry(
            id = id,
            kind = kind,
            clientId = clientId,
            connectionId = connectionId,
            meetingId = meetingId,
            createdAt = Instant.now(),
        )
        entries[id] = entry
        logger.info { "EPHEMERAL_PROMPT_REGISTERED | id=$id kind=$kind clientId=$clientId" }
        return entry
    }

    fun consume(id: String): Entry? {
        purgeExpired()
        return entries.remove(id)?.also {
            logger.info { "EPHEMERAL_PROMPT_CONSUMED | id=$id kind=${it.kind}" }
        }
    }

    fun peek(id: String): Entry? {
        purgeExpired()
        return entries[id]
    }

    private fun purgeExpired() {
        val cutoff = Instant.now().minus(TTL)
        entries.entries.removeIf { (_, e) ->
            val stale = e.createdAt.isBefore(cutoff)
            if (stale) {
                logger.debug { "EPHEMERAL_PROMPT_EXPIRED | id=${e.id} kind=${e.kind}" }
            }
            stale
        }
    }

    companion object {
        val TTL: Duration = Duration.ofMinutes(15)
    }
}
