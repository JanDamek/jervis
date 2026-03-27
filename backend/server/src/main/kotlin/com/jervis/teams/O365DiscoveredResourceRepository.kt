package com.jervis.teams

import com.jervis.common.types.ConnectionId
import kotlinx.coroutines.flow.Flow
import org.bson.types.ObjectId
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Repository

@Repository
interface O365DiscoveredResourceRepository : CoroutineCrudRepository<O365DiscoveredResourceDocument, ObjectId> {
    fun findByConnectionId(connectionId: ConnectionId): Flow<O365DiscoveredResourceDocument>

    fun findByConnectionIdAndResourceType(
        connectionId: ConnectionId,
        resourceType: String,
    ): Flow<O365DiscoveredResourceDocument>

    fun findByConnectionIdAndActive(
        connectionId: ConnectionId,
        active: Boolean,
    ): Flow<O365DiscoveredResourceDocument>

    suspend fun existsByConnectionIdAndExternalId(
        connectionId: ConnectionId,
        externalId: String,
    ): Boolean
}
