package com.jervis.repository

import com.jervis.dto.PendingTaskStateEnum
import com.jervis.dto.PendingTaskTypeEnum
import com.jervis.entity.PendingTaskDocument
import kotlinx.coroutines.flow.Flow
import org.bson.types.ObjectId
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Repository

@Repository
interface PendingTaskMongoRepository : CoroutineCrudRepository<PendingTaskDocument, ObjectId> {
    fun findAllByOrderByCreatedAtAsc(): Flow<PendingTaskDocument>

    fun findByStateOrderByCreatedAtAsc(state: PendingTaskStateEnum): Flow<PendingTaskDocument>

    fun findByTypeAndStateOrderByCreatedAtAsc(
        type: PendingTaskTypeEnum,
        state: PendingTaskStateEnum,
    ): Flow<PendingTaskDocument>

    fun findByTypeOrderByCreatedAtAsc(type: PendingTaskTypeEnum): Flow<PendingTaskDocument>

    suspend fun countByTypeAndState(
        type: PendingTaskTypeEnum,
        state: PendingTaskStateEnum,
    ): Long

    suspend fun countByType(type: PendingTaskTypeEnum): Long

    suspend fun countByState(state: PendingTaskStateEnum): Long
}
