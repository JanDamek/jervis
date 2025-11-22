package com.jervis.repository

import com.jervis.entity.PendingTaskDocument
import kotlinx.coroutines.flow.Flow
import org.bson.types.ObjectId
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Repository

@Repository
interface PendingTaskMongoRepository : CoroutineCrudRepository<PendingTaskDocument, ObjectId> {
    fun findAllByOrderByCreatedAtAsc(): Flow<PendingTaskDocument>

    fun findByStateOrderByCreatedAtAsc(state: String): Flow<PendingTaskDocument>

    fun findByTypeAndStateOrderByCreatedAtAsc(
        type: String,
        state: String,
    ): Flow<PendingTaskDocument>

    fun findByTypeOrderByCreatedAtAsc(type: String): Flow<PendingTaskDocument>

    suspend fun countByTypeAndState(
        type: String,
        state: String,
    ): Long

    suspend fun countByType(type: String): Long

    suspend fun countByState(state: String): Long
}
