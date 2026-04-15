package com.jervis.dto.urgency

import kotlinx.serialization.Serializable

/**
 * Per-client configuration for deadline-based urgency scheduling.
 *
 * See `agent://claude-code/urgency-deadline-presence-design` in KB. Inbound handlers use
 * `fastPathDeadlineMinutes` + `presenceFactor` to compute a `TaskDocument.deadline` at
 * creation time; scheduler orders by deadline ASC, watchdog bumps priority as deadline approaches.
 */
@Serializable
data class UrgencyConfigDto(
    val clientId: String,
    val defaultDeadlineMinutes: Int = 30,
    val fastPathDeadlineMinutes: FastPathDeadlinesDto = FastPathDeadlinesDto(),
    val presenceFactor: PresenceFactorDto = PresenceFactorDto(),
    val presenceTtlSeconds: Int = 120,
    val classifierBudgetPerHourPerSender: Int = 5,
    val approachingDeadlineThresholdPct: Double = 0.20,
)

/** Deadlines in minutes for structurally-detected fast-path messages. */
@Serializable
data class FastPathDeadlinesDto(
    /** Direct message in Slack/Teams/Discord — user is in 1:1 conversation. */
    val directMessage: Int = 2,
    /** Channel @mention with my user id. */
    val channelMention: Int = 5,
    /** Reply in a thread I authored, thread active within last 30 min. */
    val replyMyThreadActive: Int = 5,
    /** Reply in a thread I authored, thread stale (no recent activity). */
    val replyMyThreadStale: Int = 10,
)

/** Multiplicative factor applied to the structural deadline based on observed presence. */
@Serializable
data class PresenceFactorDto(
    /** User currently active (typing, recently sent a message). */
    val active: Double = 1.0,
    /** Away within the last 5 minutes. */
    val awayRecent: Double = 1.5,
    /** Away for hours. */
    val awayOld: Double = 5.0,
    /** User offline / unavailable. */
    val offline: Double = 10.0,
    /** No presence data (API unavailable, platform lacks support). */
    val unknown: Double = 1.0,
)

/** Observed user presence snapshot — used by detectors and stored on TaskDocument. */
@Serializable
enum class Presence {
    ACTIVE_CONVERSATION,
    LIKELY_WAITING,
    RECENTLY_ACTIVE,
    AWAY,
    OFFLINE,
    UNKNOWN,
}

/** Snapshot of a user's presence on a given platform. */
@Serializable
data class UserPresenceDto(
    val userId: String,
    val platform: String,
    val presence: Presence,
    val lastActiveAtIso: String? = null,
)
