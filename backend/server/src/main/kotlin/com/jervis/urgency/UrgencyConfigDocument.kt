package com.jervis.urgency

import com.jervis.common.types.ClientId
import com.jervis.dto.urgency.FastPathDeadlinesDto
import com.jervis.dto.urgency.Presence
import com.jervis.dto.urgency.PresenceFactorDto
import com.jervis.dto.urgency.UrgencyConfigDto
import org.bson.types.ObjectId
import org.springframework.data.annotation.Id
import org.springframework.data.annotation.PersistenceCreator
import org.springframework.data.mongodb.core.index.Indexed
import org.springframework.data.mongodb.core.mapping.Document

/**
 * Per-client urgency / deadline configuration. One document per client.
 *
 * Drives:
 *   - `StructuralUrgencyDetector` — maps DM/mention/reply to `fastPathDeadlineMinutes`
 *   - `PresenceCache` — TTL based on `presenceTtlSeconds`
 *   - Deadline-based scheduler — approaching-deadline bump at `approachingDeadlineThresholdPct`
 *
 * Missing document = defaults applied in-memory (see companion.default()).
 */
@Document(collection = "urgency_configs")
data class UrgencyConfigDocument(
    @Id
    val id: ObjectId = ObjectId.get(),
    @Indexed(unique = true)
    val clientId: ClientId,
    val defaultDeadlineMinutes: Int = 30,
    val fastPathDirectMessage: Int = 2,
    val fastPathChannelMention: Int = 5,
    val fastPathReplyMyThreadActive: Int = 5,
    val fastPathReplyMyThreadStale: Int = 10,
    val presenceFactorActive: Double = 1.0,
    val presenceFactorAwayRecent: Double = 1.5,
    val presenceFactorAwayOld: Double = 5.0,
    val presenceFactorOffline: Double = 10.0,
    val presenceFactorUnknown: Double = 1.0,
    val presenceTtlSeconds: Int = 120,
    val classifierBudgetPerHourPerSender: Int = 5,
    val approachingDeadlineThresholdPct: Double = 0.20,
) {
    fun toDto(): UrgencyConfigDto =
        UrgencyConfigDto(
            clientId = clientId.toString(),
            defaultDeadlineMinutes = defaultDeadlineMinutes,
            fastPathDeadlineMinutes =
                FastPathDeadlinesDto(
                    directMessage = fastPathDirectMessage,
                    channelMention = fastPathChannelMention,
                    replyMyThreadActive = fastPathReplyMyThreadActive,
                    replyMyThreadStale = fastPathReplyMyThreadStale,
                ),
            presenceFactor =
                PresenceFactorDto(
                    active = presenceFactorActive,
                    awayRecent = presenceFactorAwayRecent,
                    awayOld = presenceFactorAwayOld,
                    offline = presenceFactorOffline,
                    unknown = presenceFactorUnknown,
                ),
            presenceTtlSeconds = presenceTtlSeconds,
            classifierBudgetPerHourPerSender = classifierBudgetPerHourPerSender,
            approachingDeadlineThresholdPct = approachingDeadlineThresholdPct,
        )

    companion object {
        /** Factory to build a document from a DTO (full replace). */
        fun fromDto(dto: UrgencyConfigDto, existingId: ObjectId? = null): UrgencyConfigDocument =
            UrgencyConfigDocument(
                id = existingId ?: ObjectId.get(),
                clientId = ClientId(ObjectId(dto.clientId)),
                defaultDeadlineMinutes = dto.defaultDeadlineMinutes,
                fastPathDirectMessage = dto.fastPathDeadlineMinutes.directMessage,
                fastPathChannelMention = dto.fastPathDeadlineMinutes.channelMention,
                fastPathReplyMyThreadActive = dto.fastPathDeadlineMinutes.replyMyThreadActive,
                fastPathReplyMyThreadStale = dto.fastPathDeadlineMinutes.replyMyThreadStale,
                presenceFactorActive = dto.presenceFactor.active,
                presenceFactorAwayRecent = dto.presenceFactor.awayRecent,
                presenceFactorAwayOld = dto.presenceFactor.awayOld,
                presenceFactorOffline = dto.presenceFactor.offline,
                presenceFactorUnknown = dto.presenceFactor.unknown,
                presenceTtlSeconds = dto.presenceTtlSeconds,
                classifierBudgetPerHourPerSender = dto.classifierBudgetPerHourPerSender,
                approachingDeadlineThresholdPct = dto.approachingDeadlineThresholdPct,
            )

        /** Default config returned when no document exists for a client. */
        fun default(clientId: ClientId): UrgencyConfigDocument = UrgencyConfigDocument(clientId = clientId)

        /** Spring Data factory to deserialize the Kotlin inline value class ClientId. */
        @PersistenceCreator
        @JvmStatic
        fun create(
            id: ObjectId,
            clientId: ObjectId,
            defaultDeadlineMinutes: Int?,
            fastPathDirectMessage: Int?,
            fastPathChannelMention: Int?,
            fastPathReplyMyThreadActive: Int?,
            fastPathReplyMyThreadStale: Int?,
            presenceFactorActive: Double?,
            presenceFactorAwayRecent: Double?,
            presenceFactorAwayOld: Double?,
            presenceFactorOffline: Double?,
            presenceFactorUnknown: Double?,
            presenceTtlSeconds: Int?,
            classifierBudgetPerHourPerSender: Int?,
            approachingDeadlineThresholdPct: Double?,
        ): UrgencyConfigDocument =
            UrgencyConfigDocument(
                id = id,
                clientId = ClientId(clientId),
                defaultDeadlineMinutes = defaultDeadlineMinutes ?: 30,
                fastPathDirectMessage = fastPathDirectMessage ?: 2,
                fastPathChannelMention = fastPathChannelMention ?: 5,
                fastPathReplyMyThreadActive = fastPathReplyMyThreadActive ?: 5,
                fastPathReplyMyThreadStale = fastPathReplyMyThreadStale ?: 10,
                presenceFactorActive = presenceFactorActive ?: 1.0,
                presenceFactorAwayRecent = presenceFactorAwayRecent ?: 1.5,
                presenceFactorAwayOld = presenceFactorAwayOld ?: 5.0,
                presenceFactorOffline = presenceFactorOffline ?: 10.0,
                presenceFactorUnknown = presenceFactorUnknown ?: 1.0,
                presenceTtlSeconds = presenceTtlSeconds ?: 120,
                classifierBudgetPerHourPerSender = classifierBudgetPerHourPerSender ?: 5,
                approachingDeadlineThresholdPct = approachingDeadlineThresholdPct ?: 0.20,
            )
    }
}

/** In-memory snapshot of presence (populated by per-platform PresenceCache). Not persisted. */
data class PresenceSnapshot(
    val userId: String,
    val platform: String,
    val presence: Presence,
    val observedAtMillis: Long,
)
