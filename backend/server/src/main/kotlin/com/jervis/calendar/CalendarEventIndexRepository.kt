package com.jervis.calendar

import com.jervis.common.types.ConnectionId
import com.jervis.infrastructure.polling.PollingStatusEnum
import kotlinx.coroutines.flow.Flow
import org.bson.types.ObjectId
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Repository

@Repository
interface CalendarEventIndexRepository : CoroutineCrudRepository<CalendarEventIndexDocument, ObjectId> {
    suspend fun existsByConnectionIdAndEventId(
        connectionId: ConnectionId,
        eventId: String,
    ): Boolean

    fun findByStateOrderByStartTimeAsc(state: PollingStatusEnum): Flow<CalendarEventIndexDocument>
}
