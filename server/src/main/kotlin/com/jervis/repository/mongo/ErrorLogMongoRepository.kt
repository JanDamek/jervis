package com.jervis.repository

import com.jervis.entity.ErrorLogDocument
import org.bson.types.ObjectId
import org.springframework.data.domain.Pageable
import org.springframework.data.mongodb.repository.MongoRepository
import org.springframework.stereotype.Repository

@Repository
interface ErrorLogMongoRepository : MongoRepository<ErrorLogDocument, ObjectId> {
    fun findAllByClientIdOrderByCreatedAtDesc(clientId: ObjectId, pageable: Pageable): List<ErrorLogDocument>
    fun deleteAllByClientId(clientId: ObjectId): Long
}
