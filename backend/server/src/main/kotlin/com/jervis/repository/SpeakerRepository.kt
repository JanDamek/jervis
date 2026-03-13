package com.jervis.repository

import com.jervis.common.types.ClientId
import com.jervis.entity.SpeakerDocument
import kotlinx.coroutines.flow.Flow
import org.bson.types.ObjectId
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Repository

@Repository
interface SpeakerRepository : CoroutineCrudRepository<SpeakerDocument, ObjectId> {
    fun findByClientIdsContainingOrderByNameAsc(clientId: ClientId): Flow<SpeakerDocument>
    fun findAllByOrderByNameAsc(): Flow<SpeakerDocument>
}
