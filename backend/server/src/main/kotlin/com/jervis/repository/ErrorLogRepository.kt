package com.jervis.repository

import com.jervis.entity.ErrorLogDocument
import kotlinx.coroutines.flow.Flow
import org.bson.types.ObjectId
import org.springframework.data.domain.Pageable
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Repository

@Repository
interface ErrorLogRepository : CoroutineCrudRepository<ErrorLogDocument, ObjectId> {
    fun findAllByClientIdOrderByCreatedAtDesc(
        clientId: ObjectId,
        pageable: Pageable,
    ): Flow<ErrorLogDocument>

    fun findAllByOrderByCreatedAtDesc(pageable: Pageable): Flow<ErrorLogDocument>

    suspend fun deleteAllByClientId(clientId: ObjectId): Long
}
