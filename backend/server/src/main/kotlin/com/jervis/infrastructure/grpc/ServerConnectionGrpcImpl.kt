package com.jervis.infrastructure.grpc

import com.jervis.common.types.ConnectionId
import com.jervis.connection.ConnectionRepository
import com.jervis.contracts.server.ApproveReloginRequest
import com.jervis.contracts.server.ApproveReloginResponse
import com.jervis.contracts.server.ServerConnectionServiceGrpcKt
import io.grpc.Status
import io.grpc.StatusException
import mu.KotlinLogging
import org.bson.types.ObjectId
import org.springframework.stereotype.Component

// Dispatches off-hours relogin approvals to the per-connection browser
// pod agent via O365BrowserPoolServiceGrpc.PushInstruction (pod-to-pod
// HTTP removed in V6a). The instruction is a plain typed string that
// the pod agent replays against its own state machine.
@Component
class ServerConnectionGrpcImpl(
    private val connectionRepository: ConnectionRepository,
    private val browserPoolGrpc: O365BrowserPoolGrpcClient,
) : ServerConnectionServiceGrpcKt.ServerConnectionServiceCoroutineImplBase() {
    private val logger = KotlinLogging.logger {}

    override suspend fun approveRelogin(request: ApproveReloginRequest): ApproveReloginResponse {
        val raw = request.connectionId.takeIf { it.isNotBlank() }
            ?: throw StatusException(Status.INVALID_ARGUMENT.withDescription("connection_id required"))

        val connectionId = try {
            ConnectionId(ObjectId(raw))
        } catch (_: Exception) {
            throw StatusException(Status.INVALID_ARGUMENT.withDescription("invalid connection_id"))
        }

        val connection = connectionRepository.getById(connectionId)
            ?: throw StatusException(Status.NOT_FOUND.withDescription("connection not found"))

        val clientId = connection.o365ClientId ?: connection.id.toString()
        val instruction =
            "INSTRUCTION: approve_relogin. User approved off-hours " +
                "login. Proceed: if pod_state=AUTHENTICATING and credentials " +
                "available, fill_credentials(field='password') then press " +
                "Enter, handle MFA normally. Do NOT re-check is_work_hours " +
                "or query_user_activity — approval is granted."

        val resp = browserPoolGrpc.pushInstruction(connectionId, clientId, instruction)
        val queued = resp.status == "queued"
        logger.info { "RELOGIN_DISPATCH | connection=$raw pod_status=${resp.status}" }

        return ApproveReloginResponse.newBuilder()
            .setConnectionId(raw)
            .setState(if (queued) "DISPATCHED" else "REJECTED")
            .setPodStatus(if (queued) 200 else 409)
            .build()
    }
}
