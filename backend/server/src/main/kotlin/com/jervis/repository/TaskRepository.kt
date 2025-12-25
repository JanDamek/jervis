package com.jervis.repository

import com.jervis.dto.TaskStateEnum
import com.jervis.dto.TaskTypeEnum
import com.jervis.entity.TaskDocument
import com.jervis.types.ClientId
import com.jervis.types.ProjectId
import com.jervis.types.TaskId
import kotlinx.coroutines.flow.Flow
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Repository
import java.time.Instant

@Repository
interface TaskRepository : CoroutineCrudRepository<TaskDocument, TaskId> {
    suspend fun findAllByOrderByCreatedAtAsc(): Flow<TaskDocument>

    suspend fun findOneByScheduledAtLessThanAndTypeOrderByScheduledAtAsc(
        scheduledAt: Instant = Instant.now(),
        type: TaskTypeEnum = TaskTypeEnum.SCHEDULED_TASK,
    ): TaskDocument?

    suspend fun findByStateOrderByCreatedAtAsc(state: TaskStateEnum): Flow<TaskDocument>

    suspend fun findByTypeAndStateOrderByCreatedAtAsc(
        type: TaskTypeEnum,
        state: TaskStateEnum,
    ): Flow<TaskDocument>

    suspend fun findByTypeOrderByCreatedAtAsc(type: TaskTypeEnum): Flow<TaskDocument>

    suspend fun countByTypeAndState(
        type: TaskTypeEnum,
        state: TaskStateEnum,
    ): Long

    suspend fun countByType(type: TaskTypeEnum): Long

    suspend fun countByState(state: TaskStateEnum): Long

    suspend fun findByProjectIdAndType(
        projectId: ProjectId,
        type: TaskTypeEnum,
    ): Flow<TaskDocument>

    suspend fun findByClientIdAndType(
        clientId: ClientId,
        type: TaskTypeEnum,
    ): Flow<TaskDocument>
}
