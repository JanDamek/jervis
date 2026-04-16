package com.jervis.infrastructure.grpc

import com.jervis.common.types.ConnectionId
import com.jervis.contracts.server.DiscoveredResource
import com.jervis.contracts.server.ListDiscoveredRequest
import com.jervis.contracts.server.ListDiscoveredResponse
import com.jervis.contracts.server.ServerO365DiscoveredResourcesServiceGrpcKt
import com.jervis.teams.O365DiscoveredResourceRepository
import io.grpc.Status
import io.grpc.StatusException
import kotlinx.coroutines.flow.toList
import mu.KotlinLogging
import org.bson.types.ObjectId
import org.springframework.stereotype.Component

@Component
class ServerO365DiscoveredResourcesGrpcImpl(
    private val discoveredRepo: O365DiscoveredResourceRepository,
) : ServerO365DiscoveredResourcesServiceGrpcKt.ServerO365DiscoveredResourcesServiceCoroutineImplBase() {
    private val logger = KotlinLogging.logger {}

    override suspend fun listDiscovered(request: ListDiscoveredRequest): ListDiscoveredResponse {
        val raw = request.connectionId.takeIf { it.isNotBlank() }
            ?: throw StatusException(Status.INVALID_ARGUMENT.withDescription("connection_id required"))
        val connectionId = try {
            ConnectionId(ObjectId(raw))
        } catch (_: Exception) {
            throw StatusException(Status.INVALID_ARGUMENT.withDescription("invalid connection_id"))
        }

        val resourceType = request.resourceType.takeIf { it.isNotBlank() }
        val rows = if (resourceType == null) {
            discoveredRepo.findByConnectionId(connectionId).toList()
        } else {
            discoveredRepo.findByConnectionIdAndResourceType(connectionId, resourceType).toList()
        }

        val items = rows.map { r ->
            DiscoveredResource.newBuilder()
                .setExternalId(r.externalId)
                .setResourceType(r.resourceType)
                .setDisplayName(r.displayName)
                .setDescription(r.description ?: "")
                .setTeamName(r.teamName ?: "")
                .setActive(r.active)
                .setDiscoveredAtEpoch(r.discoveredAt.epochSecond)
                .setLastSeenAtEpoch(r.lastSeenAt.epochSecond)
                .build()
        }
        return ListDiscoveredResponse.newBuilder().addAllResources(items).build()
    }
}
