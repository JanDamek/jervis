package com.jervis.connection

import com.jervis.common.types.ConnectionId
import com.jervis.infrastructure.grpc.O365BrowserPoolGrpcClient
import mu.KotlinLogging
import org.bson.types.ObjectId
import org.springframework.stereotype.Service

/**
 * Dispatches off-hours relogin approvals to the per-connection browser
 * pod agent via O365BrowserPoolServiceGrpc.PushInstruction. The
 * instruction is a plain typed string the pod agent replays against its
 * own state machine.
 *
 * Shared between [com.jervis.infrastructure.grpc.ServerConnectionGrpcImpl]
 * (MCP / external callers) and [com.jervis.task.UserTaskRpcImpl]
 * (ephemeral prompt answer). Keep the instruction text identical so the
 * pod agent observes the same behavior regardless of entry point.
 */
@Service
class ConnectionApprovalService(
    private val connectionRepository: ConnectionRepository,
    private val browserPoolGrpc: O365BrowserPoolGrpcClient,
) {
    private val logger = KotlinLogging.logger {}

    data class DispatchResult(
        val connectionId: String,
        val dispatched: Boolean,
        val podStatus: String,
    )

    suspend fun approveRelogin(rawConnectionId: String): DispatchResult {
        val connectionId = try {
            ConnectionId(ObjectId(rawConnectionId))
        } catch (e: Exception) {
            throw IllegalArgumentException("invalid connection_id: $rawConnectionId", e)
        }

        val connection = connectionRepository.getById(connectionId)
            ?: throw NoSuchElementException("connection not found: $rawConnectionId")

        val clientId = connection.o365ClientId ?: connection.id.toString()
        val resp = browserPoolGrpc.pushInstruction(connectionId, clientId, RELOGIN_INSTRUCTION)
        val dispatched = resp.status == "queued"
        logger.info { "RELOGIN_DISPATCH | connection=$rawConnectionId pod_status=${resp.status}" }

        return DispatchResult(
            connectionId = rawConnectionId,
            dispatched = dispatched,
            podStatus = resp.status,
        )
    }

    companion object {
        private const val RELOGIN_INSTRUCTION =
            "INSTRUCTION: approve_relogin. User approved off-hours " +
                "login. Proceed: if pod_state=AUTHENTICATING and credentials " +
                "available, fill_credentials(field='password') then press " +
                "Enter, handle MFA normally. Do NOT re-check is_work_hours " +
                "or query_user_activity — approval is granted."
    }
}
