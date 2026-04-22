package com.jervis.infrastructure.grpc

import com.jervis.connection.ConnectionApprovalService
import com.jervis.contracts.server.ApproveReloginRequest
import com.jervis.contracts.server.ApproveReloginResponse
import com.jervis.contracts.server.ServerConnectionServiceGrpcKt
import io.grpc.Status
import io.grpc.StatusException
import mu.KotlinLogging
import org.springframework.stereotype.Component

// Dispatches off-hours relogin approvals to the per-connection browser
// pod agent. Delegates to [ConnectionApprovalService] so the MCP entry
// point and the UI ephemeral-prompt answer share identical behavior.
@Component
class ServerConnectionGrpcImpl(
    private val approvalService: ConnectionApprovalService,
) : ServerConnectionServiceGrpcKt.ServerConnectionServiceCoroutineImplBase() {
    private val logger = KotlinLogging.logger {}

    override suspend fun approveRelogin(request: ApproveReloginRequest): ApproveReloginResponse {
        val raw = request.connectionId.takeIf { it.isNotBlank() }
            ?: throw StatusException(Status.INVALID_ARGUMENT.withDescription("connection_id required"))

        val result = try {
            approvalService.approveRelogin(raw)
        } catch (e: IllegalArgumentException) {
            throw StatusException(Status.INVALID_ARGUMENT.withDescription(e.message))
        } catch (e: NoSuchElementException) {
            throw StatusException(Status.NOT_FOUND.withDescription(e.message))
        }

        return ApproveReloginResponse.newBuilder()
            .setConnectionId(result.connectionId)
            .setState(if (result.dispatched) "DISPATCHED" else "REJECTED")
            .setPodStatus(if (result.dispatched) 200 else 409)
            .build()
    }
}
