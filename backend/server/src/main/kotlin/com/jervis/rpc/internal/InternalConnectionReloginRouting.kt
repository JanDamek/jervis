package com.jervis.rpc.internal

import com.jervis.common.types.ConnectionId
import com.jervis.connection.BrowserPodManager
import com.jervis.connection.ConnectionRepository
import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.post
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import mu.KotlinLogging
import org.bson.types.ObjectId

private val logger = KotlinLogging.logger {}

/**
 * POST /internal/connections/{connectionId}/approve-relogin
 *
 * Off-hours relogin approval (product §18). Triggered by the orchestrator
 * MCP tool `connection_approve_relogin` when the user chat-approves an
 * `auth_request` push outside work hours.
 *
 * Dispatches an instruction to the pod agent; the agent resumes the
 * AUTHENTICATING flow (credential fill → MFA) even though the work-hours /
 * user-activity gate would normally block it.
 */
fun Routing.installInternalConnectionReloginApi(
    connectionRepository: ConnectionRepository,
    httpClient: HttpClient,
) {
    post("/internal/connections/{connectionId}/approve-relogin") {
        try {
            val raw = call.parameters["connectionId"] ?: run {
                call.respondText(
                    """{"error":"missing connectionId"}""",
                    ContentType.Application.Json, HttpStatusCode.BadRequest,
                )
                return@post
            }
            val connectionId = try {
                ConnectionId(ObjectId(raw))
            } catch (_: Exception) {
                call.respondText(
                    """{"error":"invalid connectionId"}""",
                    ContentType.Application.Json, HttpStatusCode.BadRequest,
                )
                return@post
            }

            val connection = connectionRepository.getById(connectionId)
            if (connection == null) {
                call.respondText(
                    """{"error":"connection not found"}""",
                    ContentType.Application.Json, HttpStatusCode.NotFound,
                )
                return@post
            }

            val podUrl = BrowserPodManager.serviceUrl(connectionId)
            val instruction =
                "INSTRUCTION: approve_relogin. User approved off-hours " +
                "login. Proceed: if pod_state=AUTHENTICATING and credentials " +
                "available, fill_credentials(field='password') then press " +
                "Enter, handle MFA normally. Do NOT re-check is_work_hours " +
                "or query_user_activity — approval is granted."

            val resp = httpClient.post("$podUrl/instruction/$raw") {
                contentType(ContentType.Application.Json)
                setBody(
                    buildJsonObject { put("instruction", instruction) }.toString(),
                )
            }
            val podBody = resp.bodyAsText()
            logger.info {
                "RELOGIN_DISPATCH | connection=$raw status=${resp.status.value} " +
                "body=${podBody.take(200)}"
            }

            call.respondText(
                buildJsonObject {
                    put("connectionId", raw)
                    put("state", "DISPATCHED")
                    put("podStatus", resp.status.value)
                }.toString(),
                ContentType.Application.Json,
            )
        } catch (e: Exception) {
            logger.warn(e) { "INTERNAL_API_ERROR | endpoint=connections/approve-relogin" }
            call.respondText(
                """{"error":"${e.message}"}""",
                ContentType.Application.Json, HttpStatusCode.InternalServerError,
            )
        }
    }
}
