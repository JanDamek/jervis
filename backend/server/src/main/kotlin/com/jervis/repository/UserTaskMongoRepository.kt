package com.jervis.repository

import com.jervis.entity.UserTaskDocument
import com.jervis.service.task.TaskSourceType
import com.jervis.service.task.TaskStatusEnum
import com.jervis.types.ClientId
import kotlinx.coroutines.flow.Flow
import org.bson.types.ObjectId
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Repository

@Repository
interface UserTaskMongoRepository : CoroutineCrudRepository<UserTaskDocument, ObjectId> {
    fun findActiveTasksByClientIdAndStatusIn(
        clientId: ClientId,
        statuses: List<TaskStatusEnum>,
    ): Flow<UserTaskDocument>

    suspend fun findFirstByClientIdAndSourceTypeAndStatusIn(
        clientId: ClientId,
        sourceType: TaskSourceType,
        statuses: List<TaskStatusEnum>,
    ): UserTaskDocument?
}
