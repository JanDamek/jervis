package com.jervis.repository

import com.jervis.entity.TaskHistoryDocument
import kotlinx.coroutines.flow.Flow
import org.bson.types.ObjectId
import org.springframework.data.domain.Pageable
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Repository

@Repository
interface TaskHistoryRepository : CoroutineCrudRepository<TaskHistoryDocument, ObjectId> {
    fun findAllByOrderByCompletedAtDesc(pageable: Pageable): Flow<TaskHistoryDocument>

    suspend fun countAllBy(): Long
}
