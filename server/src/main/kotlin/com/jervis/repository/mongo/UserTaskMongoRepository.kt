package com.jervis.repository.mongo

import com.jervis.entity.UserTaskDocument
import kotlinx.coroutines.flow.Flow
import org.bson.types.ObjectId
import org.springframework.data.mongodb.repository.Query
import org.springframework.data.mongodb.repository.ReactiveMongoRepository
import org.springframework.stereotype.Repository
import java.time.Instant

@Repository
interface UserTaskMongoRepository : ReactiveMongoRepository<UserTaskDocument, ObjectId> {
    fun findAllByClientIdAndStatusOrderByDueDateAsc(
        clientId: ObjectId,
        status: String,
    ): Flow<UserTaskDocument>

    fun findAllByClientIdAndStatusInOrderByDueDateAsc(
        clientId: ObjectId,
        statuses: List<String>,
    ): Flow<UserTaskDocument>

    fun findAllByClientIdAndDueDateBetweenAndStatusInOrderByDueDateAsc(
        clientId: ObjectId,
        startDate: Instant,
        endDate: Instant,
        statuses: List<String>,
    ): Flow<UserTaskDocument>

    @Query("{ 'clientId': ?0, 'status': { \$in: ?1 } }")
    fun findActiveTasksByClient(
        clientId: ObjectId,
        statuses: List<String>,
    ): Flow<UserTaskDocument>
}
