package com.jervis.repository.mongo

import com.jervis.entity.PendingTaskDocument
import kotlinx.coroutines.flow.Flow
import org.bson.types.ObjectId
import org.springframework.data.mongodb.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Repository

@Repository
interface PendingTaskMongoRepository : CoroutineCrudRepository<PendingTaskDocument, ObjectId> {
    fun findAllByOrderByCreatedAtAsc(): Flow<PendingTaskDocument>

    fun findByStateOrderByCreatedAtAsc(state: String): Flow<PendingTaskDocument>

    @Query("{ 'clientId': ?0, 'taskType': ?1, 'context.sourceUri': ?2 }")
    suspend fun findFirstByClientAndTypeAndSourceUri(
        clientId: ObjectId,
        taskType: String,
        sourceUri: String,
    ): PendingTaskDocument?
}
