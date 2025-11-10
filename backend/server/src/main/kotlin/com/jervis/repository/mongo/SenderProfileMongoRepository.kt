package com.jervis.repository.mongo

import com.jervis.entity.SenderProfileDocument
import kotlinx.coroutines.flow.Flow
import org.bson.types.ObjectId
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Repository

@Repository
interface SenderProfileMongoRepository : CoroutineCrudRepository<SenderProfileDocument, ObjectId> {
    suspend fun findByPrimaryIdentifier(primaryIdentifier: String): SenderProfileDocument?

    fun findByAliasesValueIn(values: List<String>): Flow<SenderProfileDocument>

    fun findByLastSeenAtBefore(timestamp: java.time.Instant): Flow<SenderProfileDocument>

    fun findByTopicsContaining(topic: String): Flow<SenderProfileDocument>
}
