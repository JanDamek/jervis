package com.jervis.meeting

import com.jervis.common.types.ClientId
import com.jervis.common.types.ProjectId
import com.jervis.dto.meeting.MeetingTypeEnum
import com.jervis.dto.meeting.MeetingStateEnum
import kotlinx.coroutines.flow.Flow
import org.bson.types.ObjectId
import org.springframework.data.mongodb.repository.Query
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

    // Global (all clients) listing for Global scope in UI
    fun findByDeletedIsFalseOrderByStartedAtDesc(): Flow<MeetingDocument>

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

    // Unclassified meetings (clientId is null, not deleted)
    fun findByClientIdIsNullAndDeletedIsFalseOrderByStartedAtDesc(): Flow<MeetingDocument>

    // Date-range queries for timeline grouping
    fun findByClientIdAndDeletedIsFalseAndStartedAtGreaterThanEqualOrderByStartedAtDesc(
        clientId: ClientId,
        startedAt: java.time.Instant,
    ): Flow<MeetingDocument>

    fun findByClientIdAndProjectIdAndDeletedIsFalseAndStartedAtGreaterThanEqualOrderByStartedAtDesc(
        clientId: ClientId,
        projectId: ProjectId,
        startedAt: java.time.Instant,
    ): Flow<MeetingDocument>

    fun findByClientIdAndDeletedIsFalseAndStartedAtLessThanOrderByStartedAtDesc(
        clientId: ClientId,
        startedAt: java.time.Instant,
    ): Flow<MeetingDocument>

    fun findByClientIdAndProjectIdAndDeletedIsFalseAndStartedAtLessThanOrderByStartedAtDesc(
        clientId: ClientId,
        projectId: ProjectId,
        startedAt: java.time.Instant,
    ): Flow<MeetingDocument>

    // Global timeline queries (all clients)
    fun findByDeletedIsFalseAndStartedAtGreaterThanEqualOrderByStartedAtDesc(
        startedAt: java.time.Instant,
    ): Flow<MeetingDocument>

    fun findByDeletedIsFalseAndStartedAtLessThanOrderByStartedAtDesc(
        startedAt: java.time.Instant,
    ): Flow<MeetingDocument>

    // Deduplication: find active meeting with same client + type (for idempotent startRecording)
    @Query("{ 'clientId': ?0, 'meetingType': ?1, 'state': { '\$in': ['RECORDING', 'UPLOADING'] }, 'deleted': false }")
    suspend fun findActiveByClientIdAndMeetingType(
        clientId: ClientId,
        meetingType: MeetingTypeEnum,
    ): MeetingDocument?

    // Merge suggestion: find meetings with same (clientId, projectId, meetingType), overlapping time ±10min
    @Query("{ 'clientId': ?0, 'projectId': ?1, 'meetingType': ?2, '_id': { '\$ne': ?3 }, 'deleted': false, 'startedAt': { '\$gte': ?4, '\$lte': ?5 } }")
    fun findOverlapping(
        clientId: ClientId,
        projectId: ProjectId?,
        meetingType: MeetingTypeEnum,
        excludeId: ObjectId,
        from: java.time.Instant,
        to: java.time.Instant,
    ): Flow<MeetingDocument>

    @Query("{ 'clientId': ?0, 'deleted': false, 'startedAt': { \$gte: ?1, \$lt: ?2 } }", sort = "{ 'startedAt': -1 }")
    fun listMeetingsInRange(
        clientId: ClientId,
        from: java.time.Instant,
        to: java.time.Instant,
    ): Flow<MeetingDocument>

    @Query("{ 'clientId': ?0, 'projectId': ?1, 'deleted': false, 'startedAt': { \$gte: ?2, \$lt: ?3 } }", sort = "{ 'startedAt': -1 }")
    fun listMeetingsInRangeForProject(
        clientId: ClientId,
        projectId: ProjectId,
        from: java.time.Instant,
        to: java.time.Instant,
    ): Flow<MeetingDocument>

    @Query("{ 'deleted': false, 'startedAt': { \$gte: ?0, \$lt: ?1 } }", sort = "{ 'startedAt': -1 }")
    fun listMeetingsInRangeAllClients(
        from: java.time.Instant,
        to: java.time.Instant,
    ): Flow<MeetingDocument>

}
