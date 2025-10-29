package com.jervis.repository.mongo

import com.jervis.entity.EmailAccountDocument
import kotlinx.coroutines.flow.Flow
import org.bson.types.ObjectId
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Repository

@Repository
interface EmailAccountMongoRepository : CoroutineCrudRepository<EmailAccountDocument, ObjectId> {
    fun findByClientId(clientId: ObjectId): Flow<EmailAccountDocument>

    fun findByProjectId(projectId: ObjectId): Flow<EmailAccountDocument>

    suspend fun findFirstByIsActiveTrueOrderByLastPolledAtAsc(): EmailAccountDocument?

    fun findAllByIsActiveTrue(): Flow<EmailAccountDocument>
}
