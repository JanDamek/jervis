package com.jervis.repository.mongo

import com.jervis.entity.PendingTaskDocument
import kotlinx.coroutines.flow.Flow
import org.bson.types.ObjectId
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Repository

@Repository
interface PendingTaskMongoRepository : CoroutineCrudRepository<PendingTaskDocument, ObjectId> {
    fun findAllByOrderByCreatedAtAsc(): Flow<PendingTaskDocument>

    fun findByStateOrderByCreatedAtAsc(state: String): Flow<PendingTaskDocument>
}
