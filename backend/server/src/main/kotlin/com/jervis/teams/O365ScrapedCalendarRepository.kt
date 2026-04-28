package com.jervis.teams

import kotlinx.coroutines.flow.Flow
import org.bson.types.ObjectId
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Repository

@Repository
interface O365ScrapedCalendarRepository : CoroutineCrudRepository<O365ScrapedCalendarDocument, ObjectId> {
    fun findByConnectionIdAndState(connectionId: ObjectId, state: String): Flow<O365ScrapedCalendarDocument>
}
