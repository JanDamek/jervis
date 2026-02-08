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

    fun findByClientIdOrderByStartedAtDesc(clientId: ClientId): Flow<MeetingDocument>

    fun findByClientIdAndProjectIdOrderByStartedAtDesc(
        clientId: ClientId,
        projectId: ProjectId,
    ): Flow<MeetingDocument>

    fun findByStateOrderByStoppedAtAsc(state: MeetingStateEnum): Flow<MeetingDocument>
}
