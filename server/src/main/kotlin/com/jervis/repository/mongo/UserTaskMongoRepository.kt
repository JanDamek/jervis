package com.jervis.repository.mongo

import com.jervis.entity.UserTaskDocument
import kotlinx.coroutines.flow.Flow
import org.bson.types.ObjectId
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Repository
import java.time.Instant

@Repository
interface UserTaskMongoRepository : CoroutineCrudRepository<UserTaskDocument, ObjectId> {
    fun findAllByClientIdAndDueDateBetweenAndStatusInOrderByDueDateAsc(
        clientId: ObjectId,
        startDate: Instant,
        endDate: Instant,
        statuses: List<String>,
    ): Flow<UserTaskDocument>

    fun findActiveTasksByClientIdAndStatusIn(
        clientId: ObjectId,
        statuses: List<String>,
    ): Flow<UserTaskDocument>
}
