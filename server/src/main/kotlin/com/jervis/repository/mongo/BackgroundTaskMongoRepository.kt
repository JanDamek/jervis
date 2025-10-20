package com.jervis.repository.mongo

import com.jervis.entity.mongo.BackgroundTaskDocument
import kotlinx.coroutines.flow.Flow
import org.bson.types.ObjectId
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Repository

@Repository
interface BackgroundTaskMongoRepository : CoroutineCrudRepository<BackgroundTaskDocument, ObjectId> {
    fun findByStatusInOrderByPriorityAscCreatedAtAsc(statuses: List<String>): Flow<BackgroundTaskDocument>

    fun findByStatusOrderByCreatedAtDesc(status: String): Flow<BackgroundTaskDocument>

    suspend fun countByStatus(status: String): Long
}
