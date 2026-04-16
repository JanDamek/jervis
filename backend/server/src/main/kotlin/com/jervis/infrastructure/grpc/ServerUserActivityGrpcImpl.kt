package com.jervis.infrastructure.grpc

import com.jervis.contracts.server.LastActivityRequest
import com.jervis.contracts.server.LastActivityResponse
import com.jervis.contracts.server.ServerUserActivityServiceGrpcKt
import com.jervis.rpc.NotificationRpcImpl
import io.grpc.Status
import io.grpc.StatusException
import org.springframework.stereotype.Component
import java.time.Instant

@Component
class ServerUserActivityGrpcImpl(
    private val notificationRpc: NotificationRpcImpl,
) : ServerUserActivityServiceGrpcKt.ServerUserActivityServiceCoroutineImplBase() {
    override suspend fun lastActivity(request: LastActivityRequest): LastActivityResponse {
        val clientId = request.clientId.takeIf { it.isNotBlank() }
            ?: throw StatusException(Status.INVALID_ARGUMENT.withDescription("client_id required"))
        val lastActive = notificationRpc.lastActiveAt(clientId)
        val seconds = if (lastActive == null) {
            Long.MAX_VALUE / 2
        } else {
            Instant.now().epochSecond - lastActive.epochSecond
        }
        return LastActivityResponse.newBuilder().setLastActiveSeconds(seconds).build()
    }
}
