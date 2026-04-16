package com.jervis.rpc.internal

import com.jervis.common.types.ConnectionId
import com.jervis.teams.O365DiscoveredResourceDocument
import com.jervis.teams.O365DiscoveredResourceRepository
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import mu.KotlinLogging
import org.bson.types.ObjectId
import java.time.Instant

private val logger = KotlinLogging.logger {}
private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

@Serializable
private data class DiscoveredResourceDto(
    val externalId: String,
    val resourceType: String,
    val displayName: String,
    val description: String? = null,
    val teamName: String? = null,
    val active: Boolean,
    val discoveredAtEpoch: Long,
    val lastSeenAtEpoch: Long,
)

private fun O365DiscoveredResourceDocument.toDto() = DiscoveredResourceDto(
    externalId = externalId,
    resourceType = resourceType,
    displayName = displayName,
    description = description,
    teamName = teamName,
    active = active,
    discoveredAtEpoch = discoveredAt.epochSecond,
    lastSeenAtEpoch = lastSeenAt.epochSecond,
)

/**
 * GET /internal/o365/discovered-resources?connectionId=<id>&resourceType=chat
 *
 * Lists discovered O365 resources (chats, channels, teams) for a Connection.
 * Used by Settings UI to let users bind specific chats/channels to projects
 * via ProjectResource mappings. Nothing is auto-bound — overlap across
 * projects is allowed.
 */
fun Routing.installInternalO365DiscoveredResourcesApi(
    discoveredRepo: O365DiscoveredResourceRepository,
) {
    get("/internal/o365/discovered-resources") {
        try {
            val connectionIdParam = call.request.queryParameters["connectionId"]
            if (connectionIdParam.isNullOrBlank()) {
                call.respondText(
                    """{"error":"missing connectionId"}""",
                    ContentType.Application.Json, HttpStatusCode.BadRequest,
                )
                return@get
            }
            val connectionId = try {
                ConnectionId(ObjectId(connectionIdParam))
            } catch (_: Exception) {
                call.respondText(
                    """{"error":"invalid connectionId"}""",
                    ContentType.Application.Json, HttpStatusCode.BadRequest,
                )
                return@get
            }
            val resourceType = call.request.queryParameters["resourceType"]

            val rows = if (resourceType.isNullOrBlank()) {
                discoveredRepo.findByConnectionId(connectionId).toList()
            } else {
                discoveredRepo.findByConnectionIdAndResourceType(connectionId, resourceType).toList()
            }

            val dtos = rows.map { it.toDto() }
            call.respondText(
                json.encodeToString(dtos),
                ContentType.Application.Json, HttpStatusCode.OK,
            )
        } catch (e: Exception) {
            logger.error(e) { "Error listing discovered resources" }
            call.respondText(
                json.encodeToString(mapOf("error" to (e.message ?: "internal error"))),
                ContentType.Application.Json, HttpStatusCode.InternalServerError,
            )
        }
    }
}

/**
 * GET /internal/user/last-activity?clientId=<id>
 *
 * Returns how many seconds ago the client's UI was last active. Used by pods
 * to decide whether to submit credentials outside work hours without explicit
 * consent.
 */
fun Routing.installInternalUserActivityApi(
    notificationRpc: com.jervis.rpc.NotificationRpcImpl,
) {
    get("/internal/user/last-activity") {
        try {
            val clientId = call.request.queryParameters["clientId"]
            if (clientId.isNullOrBlank()) {
                call.respondText(
                    """{"error":"missing clientId"}""",
                    ContentType.Application.Json, HttpStatusCode.BadRequest,
                )
                return@get
            }
            val lastActiveInstant = notificationRpc.lastActiveAt(clientId)
            val seconds = if (lastActiveInstant == null) {
                Long.MAX_VALUE / 2
            } else {
                Instant.now().epochSecond - lastActiveInstant.epochSecond
            }
            call.respondText(
                """{"last_active_seconds":$seconds}""",
                ContentType.Application.Json, HttpStatusCode.OK,
            )
        } catch (e: Exception) {
            logger.error(e) { "Error reading user activity" }
            call.respondText(
                json.encodeToString(mapOf("error" to (e.message ?: "internal error"))),
                ContentType.Application.Json, HttpStatusCode.InternalServerError,
            )
        }
    }
}
