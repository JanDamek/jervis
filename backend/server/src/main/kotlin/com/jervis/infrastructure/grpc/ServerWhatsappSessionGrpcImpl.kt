package com.jervis.infrastructure.grpc

import com.jervis.common.types.ClientId
import com.jervis.common.types.ConnectionId
import com.jervis.common.types.SourceUrn
import com.jervis.connection.ConnectionRepository
import com.jervis.contracts.server.ServerWhatsappSessionServiceGrpcKt
import com.jervis.contracts.server.WhatsappCapabilitiesRequest
import com.jervis.contracts.server.WhatsappCapabilitiesResponse
import com.jervis.contracts.server.WhatsappSessionEventRequest
import com.jervis.contracts.server.WhatsappSessionEventResponse
import com.jervis.dto.connection.ConnectionCapability
import com.jervis.dto.connection.ConnectionStateEnum
import com.jervis.dto.task.TaskStateEnum
import com.jervis.dto.task.TaskTypeEnum
import com.jervis.infrastructure.notification.ApnsPushService
import com.jervis.infrastructure.notification.FcmPushService
import com.jervis.rpc.NotificationRpcImpl
import com.jervis.task.TaskDocument
import com.jervis.task.TaskRepository
import io.grpc.Status
import io.grpc.StatusException
import mu.KotlinLogging
import org.bson.types.ObjectId
import org.springframework.stereotype.Component
import java.time.Instant

@Component
class ServerWhatsappSessionGrpcImpl(
    private val connectionRepository: ConnectionRepository,
    private val taskRepository: TaskRepository,
    private val notificationRpc: NotificationRpcImpl,
    private val fcmPushService: FcmPushService,
    private val apnsPushService: ApnsPushService,
) : ServerWhatsappSessionServiceGrpcKt.ServerWhatsappSessionServiceCoroutineImplBase() {
    private val logger = KotlinLogging.logger {}

    override suspend fun sessionEvent(request: WhatsappSessionEventRequest): WhatsappSessionEventResponse {
        logger.info { "WhatsApp session event: state=${request.state} for connection=${request.connectionId}" }

        val connectionId = try {
            ConnectionId(ObjectId(request.connectionId))
        } catch (_: Exception) {
            throw StatusException(Status.INVALID_ARGUMENT.withDescription("invalid connection_id"))
        }
        val connection = connectionRepository.getById(connectionId)
            ?: throw StatusException(Status.NOT_FOUND.withDescription("connection not found"))

        val clientId = try {
            ClientId(ObjectId(request.clientId.ifBlank { error("clientId required") }))
        } catch (_: Exception) {
            throw StatusException(Status.INVALID_ARGUMENT.withDescription("invalid or missing client_id"))
        }

        when (request.state) {
            "EXPIRED", "PHONE_DISCONNECTED" -> {
                val title = "WhatsApp: Session vypršela — naskenujte QR kód znovu"
                val description = buildString {
                    appendLine("Připojení **${connection.name}** ztratilo přihlášení k WhatsApp.")
                    appendLine("Otevřete nastavení připojení a naskenujte QR kód znovu.")
                    request.vncUrl.takeIf { it.isNotBlank() }
                        ?.let { appendLine("\n[Otevřít vzdálený přístup k prohlížeči]($it)") }
                }

                val task = TaskDocument(
                    clientId = clientId,
                    taskName = title,
                    content = description,
                    state = TaskStateEnum.USER_TASK,
                    type = TaskTypeEnum.SYSTEM,
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
                    runCatching {
                        fcmPushService.sendPushNotification(clientId.toString(), "WhatsApp", title, pushData)
                    }.onFailure { logger.warn { "FCM push failed for $clientId: ${it.message}" } }
                    runCatching {
                        apnsPushService.sendPushNotification(clientId.toString(), "WhatsApp", title, pushData)
                    }.onFailure { logger.warn { "APNs push failed for $clientId: ${it.message}" } }
                }
            }

            else -> {
                logger.debug { "Ignoring WhatsApp session event state=${request.state} for ${request.connectionId}" }
            }
        }
        return WhatsappSessionEventResponse.newBuilder().setOk(true).build()
    }

    override suspend fun capabilitiesDiscovered(request: WhatsappCapabilitiesRequest): WhatsappCapabilitiesResponse {
        logger.info { "WhatsApp capabilities discovered for connection=${request.connectionId}" }

        val connectionId = try {
            ConnectionId(ObjectId(request.connectionId))
        } catch (_: Exception) {
            throw StatusException(Status.INVALID_ARGUMENT.withDescription("invalid connection_id"))
        }
        val connection = connectionRepository.getById(connectionId)
            ?: throw StatusException(Status.NOT_FOUND.withDescription("connection not found"))

        val capabilities = setOf(ConnectionCapability.CHAT_READ)
        connectionRepository.save(
            connection.copy(
                availableCapabilities = capabilities,
                state = ConnectionStateEnum.VALID,
            ),
        )
        logger.info { "WhatsApp connection ${request.connectionId} state → VALID" }

        val clientId = request.clientId.takeIf { it.isNotBlank() }
        if (clientId != null) {
            notificationRpc.emitConnectionStateChanged(
                clientId = clientId,
                connectionId = request.connectionId,
                connectionName = connection.name,
                newState = "VALID",
                message = "WhatsApp připojení ${connection.name}: připojeno a aktivní",
            )
        } else {
            logger.warn { "No clientId in capabilities callback — skipping real-time notification" }
        }

        return WhatsappCapabilitiesResponse.newBuilder().setOk(true).build()
    }
}
