package com.jervis.urgency

import com.jervis.dto.urgency.Presence
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant

/**
 * Derives a task deadline + presence hint from the **structural** shape of an inbound chat
 * message (Slack / Teams / Discord / Email). Pure functions — no LLM, no I/O.
 *
 * The caller supplies an [InboundSignal] describing the message structurally; the detector
 * returns an [UrgencyDecision] the handler then writes onto the new TaskDocument. Ambiguous
 * channel messages (no mention, no reply-to-me) return `deadline = null`, signalling the
 * caller to fall back to `UrgencyConfigDocument.defaultDeadlineMinutes` and optionally run
 * the LLM UrgencyClassifier (separate workstream).
 */
@Component
class StructuralUrgencyDetector {
    fun decide(
        signal: InboundSignal,
        config: UrgencyConfigDocument,
        presence: Presence = Presence.UNKNOWN,
        now: Instant = Instant.now(),
    ): UrgencyDecision {
        val structuralMinutes = structuralDeadlineMinutes(signal, config) ?: return UrgencyDecision(
            deadline = null,
            presence = overlayPresence(signal, presence),
            source = UrgencyDecisionSource.AMBIGUOUS,
        )

        val factor = presenceFactor(presence, config)
        val effectiveMinutes = (structuralMinutes.toDouble() * factor).coerceAtLeast(1.0)
        return UrgencyDecision(
            deadline = now.plus(Duration.ofSeconds((effectiveMinutes * 60).toLong())),
            presence = overlayPresence(signal, presence),
            source = UrgencyDecisionSource.STRUCTURAL,
        )
    }

    private fun structuralDeadlineMinutes(
        signal: InboundSignal,
        config: UrgencyConfigDocument,
    ): Int? =
        when {
            signal.isDirectMessage -> config.fastPathDirectMessage
            signal.mentionsMe -> config.fastPathChannelMention
            signal.replyToMyThread && signal.threadActive -> config.fastPathReplyMyThreadActive
            signal.replyToMyThread -> config.fastPathReplyMyThreadStale
            else -> null
        }

    private fun presenceFactor(presence: Presence, config: UrgencyConfigDocument): Double =
        when (presence) {
            Presence.ACTIVE_CONVERSATION -> config.presenceFactorActive
            Presence.LIKELY_WAITING -> config.presenceFactorActive
            Presence.RECENTLY_ACTIVE -> config.presenceFactorAwayRecent
            Presence.AWAY -> config.presenceFactorAwayOld
            Presence.OFFLINE -> config.presenceFactorOffline
            Presence.UNKNOWN -> config.presenceFactorUnknown
        }

    private fun overlayPresence(signal: InboundSignal, raw: Presence): Presence {
        // DM on a live conversation upgrades the presence signal — the user is IN the chat.
        if (signal.isDirectMessage && raw == Presence.UNKNOWN) return Presence.LIKELY_WAITING
        if (signal.mentionsMe && raw == Presence.UNKNOWN) return Presence.LIKELY_WAITING
        return raw
    }
}

/** Structural description of an inbound message — populated by platform handlers. */
data class InboundSignal(
    val platform: String,                  // "slack" | "teams" | "discord" | "email"
    val senderId: String,
    val messageType: InboundMessageType,
    val isDirectMessage: Boolean,
    val mentionsMe: Boolean,
    /** True if this message is a reply in a thread I (self-user) started. */
    val replyToMyThread: Boolean = false,
    /** True if the thread had activity within the last 30 min (live conversation). */
    val threadActive: Boolean = false,
)

enum class InboundMessageType {
    DIRECT,
    CHANNEL_MESSAGE,
    REPLY,
    EMAIL,
}

data class UrgencyDecision(
    /** Absolute deadline to write onto TaskDocument, or null when ambiguous. */
    val deadline: Instant?,
    val presence: Presence,
    val source: UrgencyDecisionSource,
)

enum class UrgencyDecisionSource {
    /** Deadline was derived from a fast-path structural signal (DM, mention, reply-to-me). */
    STRUCTURAL,
    /** No structural signal matched — handler should apply defaultDeadlineMinutes and may queue for classifier. */
    AMBIGUOUS,
}
