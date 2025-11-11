package com.jervis.repository.mongo

import com.jervis.entity.ErrorLogDocument
import kotlinx.coroutines.flow.Flow
import org.bson.types.ObjectId
import org.springframework.data.domain.Pageable
import org.springframework.data.mongodb.repository.ReactiveMongoRepository
import org.springframework.stereotype.Repository

@Repository
interface ErrorLogMongoRepository : ReactiveMongoRepository<ErrorLogDocument, ObjectId> {
    fun findAllByClientIdOrderByCreatedAtDesc(
        clientId: ObjectId,
        pageable: Pageable,
    ): Flow<ErrorLogDocument>

    fun findAllByOrderByCreatedAtDesc(
        pageable: Pageable,
    ): Flow<ErrorLogDocument>

    suspend fun deleteAllByClientId(clientId: ObjectId): Long
}
