package com.jervis.urgency

import com.jervis.common.types.ClientId
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Repository

@Repository
interface UrgencyConfigRepository : CoroutineCrudRepository<UrgencyConfigDocument, org.bson.types.ObjectId> {
    suspend fun findByClientId(clientId: ClientId): UrgencyConfigDocument?
}
