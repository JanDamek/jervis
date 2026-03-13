package com.jervis.repository

import com.jervis.entity.teams.O365ScrapeMessageDocument
import kotlinx.coroutines.flow.Flow
import org.bson.types.ObjectId
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Repository

@Repository
interface O365ScrapeMessageRepository : CoroutineCrudRepository<O365ScrapeMessageDocument, ObjectId> {
    fun findByConnectionIdAndState(connectionId: String, state: String): Flow<O365ScrapeMessageDocument>
}
