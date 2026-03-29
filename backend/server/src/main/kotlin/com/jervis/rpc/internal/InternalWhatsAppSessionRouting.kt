package com.jervis.rpc.internal

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
 * Internal REST endpoint for WhatsApp browser session callbacks.
 *
 * When the WhatsApp browser service detects session expiry (QR code reappears,
 * phone disconnected), it POSTs here so the Kotlin server can create a
 * USER_TASK notification visible in the chat UI.
 */
fun Routing.installInternalWhatsAppSessionApi(
    connectionRepository: ConnectionRepository,
    taskRepository: TaskRepository,
    notificationRpc: NotificationRpcImpl,
    fcmPushService: FcmPushService,
    apnsPushService: ApnsPushService,
) {
    post("/internal/whatsapp/session-event") {
        try {
            val body = call.receive<WhatsAppSessionEventRequest>()
            logger.info { "WhatsApp session event: state=${body.state} for connection=${body.connectionId}" }

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

            val clientId = try {
                com.jervis.common.types.ClientId(ObjectId(body.clientId ?: error("clientId is required")))
            } catch (_: Exception) {
                call.respondText(
                    """{"error":"invalid or missing clientId"}""",
                    ContentType.Application.Json,
                    HttpStatusCode.BadRequest,
                )
                return@post
            }

            when (body.state) {
                "EXPIRED", "PHONE_DISCONNECTED" -> {
                    val title = "WhatsApp: Session vypršela — naskenujte QR kód znovu"
                    val description = buildString {
                        appendLine("Připojení **${connection.name}** ztratilo přihlášení k WhatsApp.")
                        appendLine("Otevřete nastavení připojení a naskenujte QR kód znovu.")
                        body.vncUrl?.let { appendLine("\n[Otevřít vzdálený přístup k prohlížeči]($it)") }
                    }

                    val task = TaskDocument(
                        clientId = clientId,
                        taskName = title,
                        content = description,
                        state = TaskStateEnum.USER_TASK,
                        type = TaskTypeEnum.USER_TASK,
                        sourceUrn = SourceUrn("whatsapp-browser::event:session-notification"),
                        pendingUserQuestion = title,
                        userQuestionContext = description,
                        priorityScore = 70,
                        lastActivityAt = Instant.now(),
                    )
                    taskRepository.save(task)

                    notificationRpc.emitUserTaskCreated(
                        clientId = clientId.toString(),
                        taskId = task.id.toString(),
                        title = title,
                        interruptAction = "whatsapp_relogin",
                        interruptDescription = description,
                    )

                    if (!notificationRpc.hasActiveSubscribers(clientId.toString())) {
                        val pushData = mapOf(
                            "taskId" to task.id.toString(),
                            "type" to "user_task",
                            "interruptAction" to "whatsapp_relogin",
                        )
                        try {
                            fcmPushService.sendPushNotification(clientId.toString(), "WhatsApp", title, pushData)
                        } catch (e: Exception) {
                            logger.warn { "FCM push failed for $clientId: ${e.message}" }
                        }
                        try {
                            apnsPushService.sendPushNotification(clientId.toString(), "WhatsApp", title, pushData)
                        } catch (e: Exception) {
                            logger.warn { "APNs push failed for $clientId: ${e.message}" }
                        }
                    }
                }

                else -> {
                    logger.debug { "Ignoring WhatsApp session event state=${body.state} for ${body.connectionId}" }
                }
            }

            call.respondText(
                """{"status":"ok"}""",
                ContentType.Application.Json,
                HttpStatusCode.OK,
            )
        } catch (e: Exception) {
            logger.error(e) { "Error handling WhatsApp session event" }
            call.respondText(
                json.encodeToString(mapOf("error" to (e.message ?: "internal error"))),
                ContentType.Application.Json,
                HttpStatusCode.InternalServerError,
            )
        }
    }
}

/**
 * Internal REST endpoint for WhatsApp capability discovery callback.
 */
fun Routing.installInternalWhatsAppCapabilitiesApi(
    connectionRepository: ConnectionRepository,
    notificationRpc: NotificationRpcImpl,
    fcmPushService: FcmPushService,
    apnsPushService: ApnsPushService,
) {
    post("/internal/whatsapp/capabilities-discovered") {
        try {
            val body = call.receive<WhatsAppCapabilitiesDiscoveredRequest>()
            logger.info { "WhatsApp capabilities discovered for connection=${body.connectionId}" }

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

            val capabilities = setOf(ConnectionCapability.CHAT_READ)

            connectionRepository.save(
                connection.copy(
                    availableCapabilities = capabilities,
                    state = ConnectionStateEnum.VALID,
                ),
            )

            logger.info { "WhatsApp connection ${body.connectionId} state → VALID" }

            val message = "WhatsApp připojení ${connection.name}: připojeno a aktivní"

            if (body.clientId != null) {
                notificationRpc.emitConnectionStateChanged(
                    clientId = body.clientId!!,
                    connectionId = body.connectionId,
                    connectionName = connection.name,
                    newState = "VALID",
                    message = message,
                )
            } else {
                logger.warn { "No clientId in capabilities callback — skipping real-time notification" }
            }

            call.respondText(
                """{"status":"ok"}""",
                ContentType.Application.Json,
                HttpStatusCode.OK,
            )
        } catch (e: Exception) {
            logger.error(e) { "Error handling WhatsApp capabilities discovery" }
            call.respondText(
                json.encodeToString(mapOf("error" to (e.message ?: "internal error"))),
                ContentType.Application.Json,
                HttpStatusCode.InternalServerError,
            )
        }
    }
}

@Serializable
private data class WhatsAppSessionEventRequest(
    val clientId: String? = null,
    val connectionId: String,
    val state: String,
    val vncUrl: String? = null,
)

@Serializable
private data class WhatsAppCapabilitiesDiscoveredRequest(
    val clientId: String? = null,
    val connectionId: String,
    val availableCapabilities: List<String> = emptyList(),
    val chatCount: Int? = null,
)
