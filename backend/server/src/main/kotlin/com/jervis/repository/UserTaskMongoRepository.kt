package com.jervis.repository

import com.jervis.entity.UserTaskDocument
import com.jervis.service.task.TaskSourceType
import com.jervis.service.task.TaskStatusEnum
import kotlinx.coroutines.flow.Flow
import org.bson.types.ObjectId
import org.springframework.data.mongodb.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Repository
import java.time.Instant

@Repository
interface UserTaskMongoRepository : CoroutineCrudRepository<UserTaskDocument, ObjectId> {
    fun findAllByClientIdAndDueDateBetweenAndStatusInOrderByDueDateAsc(
        clientId: ObjectId,
        startDate: Instant,
        endDate: Instant,
        statuses: List<TaskStatusEnum>,
    ): Flow<UserTaskDocument>

    fun findActiveTasksByClientIdAndStatusIn(
        clientId: ObjectId,
        statuses: List<TaskStatusEnum>,
    ): Flow<UserTaskDocument>

    @Query("{ 'clientId': ?0, 'status': { \$in: ?1 }, 'metadata.threadId': ?2 }")
    fun findActiveByClientAndThreadId(
        clientId: ObjectId,
        statuses: List<TaskStatusEnum>,
        threadId: String,
    ): Flow<UserTaskDocument>

    fun findFirstByClientIdAndSourceTypeAndStatusIn(
        clientId: ObjectId,
        sourceType: TaskSourceType,
        statuses: List<TaskStatusEnum>,
    ): UserTaskDocument?
}
