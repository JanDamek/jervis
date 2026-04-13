package com.jervis.rpc.internal

import com.jervis.common.types.ClientId
import com.jervis.common.types.ConnectionId
import com.jervis.common.types.SourceUrn
import com.jervis.dto.task.TaskStateEnum
import com.jervis.dto.task.TaskTypeEnum
import com.jervis.dto.connection.ConnectionCapability
import com.jervis.dto.connection.ConnectionStateEnum
import com.jervis.task.TaskDocument
import com.jervis.connection.ConnectionRepository
import com.jervis.task.TaskRepository
import com.jervis.rpc.NotificationRpcImpl
import com.jervis.infrastructure.notification.ApnsPushService
import com.jervis.infrastructure.notification.FcmPushService
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import mu.KotlinLogging
import org.bson.types.ObjectId
import java.time.Instant

private val logger = KotlinLogging.logger {}
private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

/**
 * Internal REST endpoint for O365 browser pool session callbacks.
 *
 * When the browser pool detects MFA requirement or session expiry,
 * it POSTs here so the Kotlin server can create a USER_TASK notification
 * visible in the chat UI (with VNC link and MFA details).
 */
fun Routing.installInternalO365SessionApi(
    connectionRepository: ConnectionRepository,
    taskRepository: TaskRepository,
    notificationRpc: NotificationRpcImpl,
    fcmPushService: FcmPushService,
    apnsPushService: ApnsPushService,
) {
    post("/internal/o365/session-event") {
        try {
            val body = call.receive<O365SessionEventRequest>()
            logger.info { "O365 session event: state=${body.state} for connection=${body.connectionId}" }

            // Look up connection to find clientId
            val connectionId = try {
                ConnectionId(ObjectId(body.connectionId))
            } catch (_: Exception) {
                logger.warn { "Invalid connectionId: ${body.connectionId}" }
                call.respondText(
                    """{"error":"invalid connectionId"}""",
                    ContentType.Application.Json,
                    HttpStatusCode.BadRequest,
                )
                return@post
            }

            val connection = connectionRepository.getById(connectionId)
            if (connection == null) {
                logger.warn { "Connection not found: ${body.connectionId}" }
                call.respondText(
                    """{"error":"connection not found"}""",
                    ContentType.Application.Json,
                    HttpStatusCode.NotFound,
                )
                return@post
            }

            val clientId = com.jervis.common.types.ClientId(org.bson.types.ObjectId("68a332361b04695a243e5ae8")) // Default Jervis clientId

            when (body.state) {
                "AWAITING_MFA" -> {
                    val title = buildString {
                        append("Microsoft 365: ")
                        when (body.mfaType) {
                            "authenticator_number" -> append("Potvrďte číslo ${body.mfaNumber ?: ""} v Authenticator")
                            "authenticator_code" -> append("Zadejte kód z Authenticator")
                            "sms_code" -> append("Zadejte SMS kód")
                            "phone_call" -> append("Potvrďte telefonní hovor")
                            else -> append("Vyžadováno dvoufaktorové ověření")
                        }
                    }
                    val description = buildString {
                        appendLine("Připojení **${connection.name}** vyžaduje MFA ověření.")
                        body.mfaMessage?.let { appendLine(it) }
                        body.mfaNumber?.let { appendLine("**Číslo k potvrzení: $it**") }
                        body.vncUrl?.let { appendLine("\n[Otevřít vzdálený přístup k prohlížeči]($it)") }
                    }

                    createSessionNotification(
                        taskRepository, notificationRpc, fcmPushService, apnsPushService,
                        clientId, connection.name, title, description,
                        interruptAction = "o365_mfa",
                        browserPoolClientId = body.connectionId,
                        alwaysPush = true, // MFA is urgent — always send push regardless of UI state
                    )
                }

                "EXPIRED" -> {
                    val title = "Microsoft 365: Session vypršela — je potřeba se znovu přihlásit"
                    val description = buildString {
                        appendLine("Připojení **${connection.name}** ztratilo přihlášení.")
                        appendLine("Otevřete nastavení připojení a přihlaste se znovu.")
                        body.vncUrl?.let { appendLine("\n[Otevřít vzdálený přístup k prohlížeči]($it)") }
                    }

                    createSessionNotification(
                        taskRepository, notificationRpc, fcmPushService, apnsPushService,
                        clientId, connection.name, title, description,
                        interruptAction = "o365_relogin",
                        browserPoolClientId = body.connectionId,
                    )
                }

                else -> {
                    logger.debug { "Ignoring session event state=${body.state} for ${body.connectionId}" }
                }
            }

            call.respondText(
                """{"status":"ok"}""",
                ContentType.Application.Json,
                HttpStatusCode.OK,
            )
        } catch (e: Exception) {
            logger.error(e) { "Error handling O365 session event" }
            call.respondText(
                json.encodeToString(mapOf("error" to (e.message ?: "internal error"))),
                ContentType.Application.Json,
                HttpStatusCode.InternalServerError,
            )
        }
    }
}

private suspend fun createSessionNotification(
    taskRepository: TaskRepository,
    notificationRpc: NotificationRpcImpl,
    fcmPushService: FcmPushService,
    apnsPushService: ApnsPushService,
    clientId: ClientId,
    connectionName: String,
    title: String,
    description: String,
    interruptAction: String,
    browserPoolClientId: String? = null,
    alwaysPush: Boolean = false,
) {
    // Check if UI has active subscribers — skip push if app is open (unless alwaysPush)
    val hasActiveUi = if (alwaysPush) false else notificationRpc.hasActiveSubscribers(clientId.toString())

    val task = TaskDocument(
        clientId = clientId,
        taskName = title,
        content = description,
        state = TaskStateEnum.USER_TASK,
        type = TaskTypeEnum.SYSTEM,
        sourceUrn = SourceUrn("o365-browser-pool::event:session-notification"),
        pendingUserQuestion = title,
        userQuestionContext = description,
        priorityScore = 70, // High priority — needs immediate attention
        lastActivityAt = Instant.now(),
        // Store browser pool client ID for MFA code forwarding
        actionType = browserPoolClientId,
    )
    taskRepository.save(task)

    // Emit kRPC event for real-time UI notification
    notificationRpc.emitUserTaskCreated(
        clientId = clientId.toString(),
        taskId = task.id.toString(),
        title = title,
        interruptAction = interruptAction,
        interruptDescription = description,
    )

    // Send push notification if app is not actively connected
    if (!hasActiveUi) {
        val pushData = mapOf(
            "taskId" to task.id.toString(),
            "type" to "user_task",
            "interruptAction" to interruptAction,
        )
        try {
            fcmPushService.sendPushNotification(clientId.toString(), "Microsoft 365", title, pushData)
        } catch (e: Exception) {
            logger.warn { "FCM push failed for $clientId: ${e.message}" }
        }
        try {
            apnsPushService.sendPushNotification(clientId.toString(), "Microsoft 365", title, pushData)
        } catch (e: Exception) {
            logger.warn { "APNs push failed for $clientId: ${e.message}" }
        }
    }

    logger.info { "Created O365 session notification for $clientId: $title" }
}

/**
 * Internal REST endpoint for O365 browser pool capability discovery callback.
 *
 * After the browser pool opens tabs and detects which O365 services are
 * actually available (chat/email/calendar), it POSTs the discovered
 * capabilities here. The server updates the connection's availableCapabilities
 * and transitions state from DISCOVERING to VALID.
 */
fun Routing.installInternalO365CapabilitiesApi(
    connectionRepository: ConnectionRepository,
    notificationRpc: NotificationRpcImpl,
    fcmPushService: FcmPushService,
    apnsPushService: ApnsPushService,
) {
    post("/internal/o365/capabilities-discovered") {
        try {
            val body = call.receive<O365CapabilitiesDiscoveredRequest>()
            logger.info { "O365 capabilities discovered for connection=${body.connectionId}: ${body.availableCapabilities}" }

            val connectionId = try {
                ConnectionId(ObjectId(body.connectionId))
            } catch (_: Exception) {
                call.respondText(
                    """{"error":"invalid connectionId"}""",
                    ContentType.Application.Json,
                    HttpStatusCode.BadRequest,
                )
                return@post
            }

            val connection = connectionRepository.getById(connectionId)
            if (connection == null) {
                call.respondText(
                    """{"error":"connection not found"}""",
                    ContentType.Application.Json,
                    HttpStatusCode.NotFound,
                )
                return@post
            }

            // Map string capability names to enum values
            val capabilities = body.availableCapabilities.mapNotNull { name ->
                try {
                    ConnectionCapability.valueOf(name)
                } catch (_: IllegalArgumentException) {
                    logger.warn { "Unknown capability: $name" }
                    null
                }
            }.toSet()

            // Update connection with discovered capabilities and transition to VALID
            connectionRepository.save(
                connection.copy(
                    availableCapabilities = capabilities,
                    state = ConnectionStateEnum.VALID,
                )
            )

            logger.info {
                "Connection ${body.connectionId} capabilities updated: $capabilities (state → VALID)"
            }

            // Notify UI that discovery is complete
            val clientId = com.jervis.common.types.ClientId(org.bson.types.ObjectId("68a332361b04695a243e5ae8"))
            val capLabels = capabilities.joinToString(", ") { it.name }
            val message = if (capabilities.isEmpty()) {
                "Připojení ${connection.name}: žádné služby nenalezeny"
            } else {
                "Připojení ${connection.name}: nalezeny služby — $capLabels"
            }

            // kRPC event (in-app snackbar)
            notificationRpc.emitConnectionStateChanged(
                clientId = clientId.toString(),
                connectionId = body.connectionId,
                connectionName = connection.name,
                newState = "VALID",
                message = message,
            )

            // Push notification (FCM + APNs) for background/closed app
            if (!notificationRpc.hasActiveSubscribers(clientId.toString())) {
                val pushTitle = "Microsoft 365"
                try {
                    fcmPushService.sendPushNotification(clientId.toString(), pushTitle, message, emptyMap())
                } catch (e: Exception) {
                    logger.warn { "FCM push failed for discovery: ${e.message}" }
                }
                try {
                    apnsPushService.sendPushNotification(clientId.toString(), pushTitle, message, emptyMap())
                } catch (e: Exception) {
                    logger.warn { "APNs push failed for discovery: ${e.message}" }
                }
            }

            call.respondText(
                """{"status":"ok","capabilities":${capabilities.size}}""",
                ContentType.Application.Json,
                HttpStatusCode.OK,
            )
        } catch (e: Exception) {
            logger.error(e) { "Error handling O365 capabilities discovery" }
            call.respondText(
                json.encodeToString(mapOf("error" to (e.message ?: "internal error"))),
                ContentType.Application.Json,
                HttpStatusCode.InternalServerError,
            )
        }
    }
}

@Serializable
private data class O365CapabilitiesDiscoveredRequest(
    val connectionId: String,
    val availableCapabilities: List<String>,
)

@Serializable
private data class O365SessionEventRequest(
    val clientId: String? = null,
    val connectionId: String,
    val state: String,
    val mfaType: String? = null,
    val mfaMessage: String? = null,
    val mfaNumber: String? = null,
    val vncUrl: String? = null,
)
