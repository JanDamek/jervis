package com.jervis.infrastructure.grpc

import com.jervis.common.types.ConnectionId
import com.jervis.connection.BrowserPodManager
import com.jervis.connection.ConnectionRepository
import com.jervis.contracts.server.ApproveReloginRequest
import com.jervis.contracts.server.ApproveReloginResponse
import com.jervis.contracts.server.ServerConnectionServiceGrpcKt
import io.grpc.Status
import io.grpc.StatusException
import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import mu.KotlinLogging
import org.bson.types.ObjectId
import org.springframework.stereotype.Component

// Dispatches off-hours relogin instructions to the pod agent.
// Returns DISPATCHED + pod HTTP status on success; surfaces not-found
// and invalid-id via gRPC StatusException so callers see canonical codes.
@Component
class ServerConnectionGrpcImpl(
    private val connectionRepository: ConnectionRepository,
    private val httpClient: HttpClient,
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

        connectionRepository.getById(connectionId)
            ?: throw StatusException(Status.NOT_FOUND.withDescription("connection not found"))

        val podUrl = BrowserPodManager.serviceUrl(connectionId)
        val instruction =
            "INSTRUCTION: approve_relogin. User approved off-hours " +
                "login. Proceed: if pod_state=AUTHENTICATING and credentials " +
                "available, fill_credentials(field='password') then press " +
                "Enter, handle MFA normally. Do NOT re-check is_work_hours " +
                "or query_user_activity — approval is granted."

        val resp = httpClient.post("$podUrl/instruction/$raw") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("instruction", instruction) }.toString())
        }
        val podBody = resp.bodyAsText()
        logger.info {
            "RELOGIN_DISPATCH | connection=$raw status=${resp.status.value} body=${podBody.take(200)}"
        }

        return ApproveReloginResponse.newBuilder()
            .setConnectionId(raw)
            .setState("DISPATCHED")
            .setPodStatus(resp.status.value)
            .build()
    }
}
