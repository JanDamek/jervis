package com.jervis.repository

import com.jervis.entity.ClientReadyStatusDocument
import kotlinx.coroutines.flow.Flow
import org.bson.types.ObjectId
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Repository

@Repository
interface ClientReadyStatusRepository : CoroutineCrudRepository<ClientReadyStatusDocument, ObjectId> {
    fun findByClientId(clientId: ObjectId): Flow<ClientReadyStatusDocument>
}
