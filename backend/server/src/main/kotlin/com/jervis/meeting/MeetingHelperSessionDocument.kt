package com.jervis.meeting

import org.bson.types.ObjectId
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.index.Indexed
import org.springframework.data.mongodb.core.mapping.Document
import org.springframework.data.mongodb.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import kotlinx.coroutines.flow.Flow

/**
 * Persistence for active Meeting Helper / Claude companion sessions.
 *
 * Without persistence a server restart wiped every in-memory helper session,
 * which silently killed the live assistant mid-meeting. The recovery on
 * startup reads this collection, filters meetings still in RECORDING/UPLOADING,
 * and re-wires the in-memory state (MeetingHelperService.sessions +
 * MeetingCompanionAssistant.active) while the K8s companion Job (which has an
 * independent lifecycle) is left running.
 */
@Document(collection = "meeting_helper_sessions")
data class MeetingHelperSessionDocument(
    @Id
    val id: ObjectId = ObjectId(),
    @Indexed(unique = true)
    val meetingId: String,
    val deviceId: String,
    val sourceLang: String = "en",
    val targetLang: String = "cs",
    val createdAt: java.time.Instant = java.time.Instant.now(),
    val updatedAt: java.time.Instant = java.time.Instant.now(),
)

interface MeetingHelperSessionRepository : CoroutineCrudRepository<MeetingHelperSessionDocument, ObjectId> {
    suspend fun findByMeetingId(meetingId: String): MeetingHelperSessionDocument?
    suspend fun deleteByMeetingId(meetingId: String): Long
    @Query("{}")
    fun findAllSessions(): Flow<MeetingHelperSessionDocument>
}
