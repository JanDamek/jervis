package com.jervis.repository.mongo

import com.jervis.entity.mongo.PendingTaskDocument
import kotlinx.coroutines.flow.Flow
import org.bson.types.ObjectId
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Repository
import java.time.Instant

@Repository
interface PendingTaskMongoRepository : CoroutineCrudRepository<PendingTaskDocument, ObjectId> {
    fun findByStatusOrderByCreatedAtDesc(status: String): Flow<PendingTaskDocument>

    fun findByProjectIdAndStatusNot(
        projectId: ObjectId,
        status: String,
    ): Flow<PendingTaskDocument>

    fun findByNextRetryAtBeforeAndStatusIn(
        now: Instant,
        statuses: List<String>,
    ): Flow<PendingTaskDocument>

    suspend fun countByStatus(status: String): Long

    fun findTop10ByStatusOrderBySeverityAscCreatedAtDesc(status: String): Flow<PendingTaskDocument>
}
