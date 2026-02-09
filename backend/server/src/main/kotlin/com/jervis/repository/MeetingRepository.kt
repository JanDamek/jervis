package com.jervis.repository

import com.jervis.common.types.ClientId
import com.jervis.common.types.ProjectId
import com.jervis.dto.meeting.MeetingStateEnum
import com.jervis.entity.meeting.MeetingDocument
import kotlinx.coroutines.flow.Flow
import org.bson.types.ObjectId
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Repository

@Repository
interface MeetingRepository : CoroutineCrudRepository<MeetingDocument, ObjectId> {

    // Active meetings (normal listing, deleted=false)
    fun findByClientIdAndDeletedIsFalseOrderByStartedAtDesc(clientId: ClientId): Flow<MeetingDocument>

    fun findByClientIdAndProjectIdAndDeletedIsFalseOrderByStartedAtDesc(
        clientId: ClientId,
        projectId: ProjectId,
    ): Flow<MeetingDocument>

    // Pipeline queries — only process non-deleted meetings
    fun findByStateAndDeletedIsFalseOrderByStoppedAtAsc(state: MeetingStateEnum): Flow<MeetingDocument>

    // Stale recovery (startup) — includes all regardless of deleted flag
    fun findByStateOrderByStoppedAtAsc(state: MeetingStateEnum): Flow<MeetingDocument>

    // Trash listing
    fun findByClientIdAndDeletedIsTrueOrderByDeletedAtDesc(clientId: ClientId): Flow<MeetingDocument>

    fun findByClientIdAndProjectIdAndDeletedIsTrueOrderByDeletedAtDesc(
        clientId: ClientId,
        projectId: ProjectId,
    ): Flow<MeetingDocument>

    // Trash cleanup (older than cutoff)
    fun findByDeletedIsTrueAndDeletedAtBefore(cutoff: java.time.Instant): Flow<MeetingDocument>
}
