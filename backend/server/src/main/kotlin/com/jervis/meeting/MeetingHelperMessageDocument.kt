package com.jervis.meeting

import com.jervis.dto.meeting.HelperMessageType
import org.bson.types.ObjectId
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.index.Indexed
import org.springframework.data.mongodb.core.mapping.Document
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import kotlinx.coroutines.flow.Flow

/**
 * Historical log of every meeting helper / assistant message pushed to devices.
 *
 * Purpose: let the user re-read what the live assistant said during a meeting.
 * NOT indexed into the Knowledge Base — assistant output is reactive, context-
 * bound and may hallucinate, so the authoritative KB source remains the full
 * post-meeting diarized transcript pipeline (MeetingContinuousIndexer).
 *
 * Retention is intentionally indefinite for now; a TTL index can be added later
 * if needed.
 */
@Document(collection = "meeting_helper_messages")
data class MeetingHelperMessageDocument(
    @Id
    val id: ObjectId = ObjectId(),
    @Indexed
    val meetingId: String,
    val type: HelperMessageType,
    val text: String,
    val context: String = "",
    val fromLang: String = "",
    val toLang: String = "",
    val createdAt: java.time.Instant = java.time.Instant.now(),
)

interface MeetingHelperMessageRepository : CoroutineCrudRepository<MeetingHelperMessageDocument, ObjectId> {
    fun findByMeetingIdOrderByCreatedAtAsc(meetingId: String): Flow<MeetingHelperMessageDocument>
}
