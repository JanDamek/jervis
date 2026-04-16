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
import com.jervis.preferences.DeviceTokenRepository
import kotlinx.coroutines.flow.toList
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
import java.util.concurrent.ConcurrentHashMap

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
    deviceTokenRepository: DeviceTokenRepository? = null,
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

            // Find all client IDs with registered push devices — MFA is urgent system-level notification
            val clientIds = try {
                deviceTokenRepository?.findAll()?.toList()
                    ?.map { it.clientId }?.distinct()
                    ?.map { ClientId(ObjectId(it)) }
                    ?: listOf(ClientId(ObjectId("68a332361b04695a243e5ae8")))
            } catch (_: Exception) {
                listOf(ClientId(ObjectId("68a332361b04695a243e5ae8")))
            }

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

                    // Send to ALL clients with devices — MFA is urgent system-level notification
                    for (cid in clientIds) {
                        createSessionNotification(
                            taskRepository, notificationRpc, fcmPushService, apnsPushService,
                            cid, connection.name, title, description,
                            interruptAction = "o365_mfa",
                            browserPoolClientId = body.connectionId,
                            alwaysPush = true,
                            mfaType = body.mfaType,
                            mfaNumber = body.mfaNumber,
                        )
                    }
                }

                "EXPIRED" -> {
                    // Pod is autonomous — it has credentials and must self-heal.
                    // EXPIRED here is just a state update, NOT a user task.
                    // Pod's HealthLoop + ai_login will trigger re-login automatically.
                    // Only AWAITING_MFA warrants a user notification (user interaction needed).
                    logger.info {
                        "Session EXPIRED for ${connection.name} (${body.connectionId}) — " +
                        "pod will self-recover via ai_login, no user notification"
                    }
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
    mfaType: String? = null,
    mfaNumber: String? = null,
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
        connectionName = connectionName,
        mfaType = mfaType,
        mfaNumber = mfaNumber,
    )

    // Send push notification if app is not actively connected
    if (!hasActiveUi) {
        val pushData = buildMap {
            put("taskId", task.id.toString())
            put("type", "user_task")
            put("interruptAction", interruptAction)
            mfaType?.let { put("mfaType", it) }
            mfaNumber?.let { put("mfaNumber", it) }
        }
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
            val clientId = ClientId(ObjectId("68a332361b04695a243e5ae8"))
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

@Serializable
private data class O365NotifyRequest(
    val connectionId: String,
    val kind: String,
    val message: String,
    val chatId: String? = null,
    val chatName: String? = null,
    val sender: String? = null,
    val preview: String? = null,
    val screenshot: String? = null,
    // MFA number-match (product §17) — pod MUST send mfaCode when kind='mfa'.
    // We surface it in the push payload + body so the user can approve on
    // the phone without opening the app.
    val mfaCode: String? = null,
    // meeting_alone_check (product §10a) — MeetingDocument id for the
    // orchestrator MCP meeting_alone_leave/stay chat bubble action.
    val meetingId: String? = null,
)

private const val URGENT_DEDUP_WINDOW_MS = 60_000L
private val urgentDedupCache: MutableMap<String, Long> = ConcurrentHashMap()

private fun priorityFor(kind: String): Int = when (kind) {
    "urgent_message" -> 95
    "meeting_invite" -> 80
    "auth_request" -> 75
    "mfa" -> 70
    "meeting_alone_check" -> 65
    "error" -> 60
    else -> 40
}

private fun alwaysPushFor(kind: String): Boolean = kind in setOf(
    "urgent_message", "mfa", "auth_request", "meeting_invite", "meeting_alone_check",
)

private fun titleFor(kind: String, req: O365NotifyRequest, connectionName: String): String = when (kind) {
    "urgent_message" -> "[$connectionName] Direct od ${req.sender ?: req.chatName ?: "?"}"
    "meeting_invite" -> "[$connectionName] Schůzka: ${req.chatName ?: "příchozí hovor"}"
    "meeting_alone_check" -> "[$connectionName] V meetingu jsi sám — odejít?"
    "auth_request" -> "[$connectionName] Povolit přihlášení?"
    "mfa" -> {
        val code = req.mfaCode?.takeIf { it.isNotBlank() }
        if (code != null) "[$connectionName] Potvrď $code v Authenticatoru"
        else "[$connectionName] Dvoufaktorové ověření"
    }
    "error" -> "[$connectionName] Agent uvízl — potřebuje pomoc"
    else -> "[$connectionName] ${req.message.take(60)}"
}

private fun czechDescription(kind: String, req: O365NotifyRequest, connectionName: String): String {
    val head = when (kind) {
        "urgent_message" -> "Nová přímá zpráva v připojení **$connectionName** od **${req.sender ?: req.chatName ?: "?"}**."
        "meeting_invite" -> "V připojení **$connectionName** zvoní hovor${req.chatName?.let { " v chatu **$it**" } ?: ""}. Mám se připojit a nahrát meeting?"
        "meeting_alone_check" -> "V meetingu jsi sám — mám z něj Jervise odhlásit, nebo ještě počkat?"
        "auth_request" -> "Připojení **$connectionName** potřebuje mimo pracovní dobu schválit přihlášení."
        "mfa" -> {
            val code = req.mfaCode?.takeIf { it.isNotBlank() }
            if (code != null) "Připojení **$connectionName**: potvrď číslo **$code** v Microsoft Authenticator."
            else "Připojení **$connectionName** vyžaduje dvoufaktorové ověření."
        }
        "error" -> "Agent připojení **$connectionName** se zasekl a potřebuje zásah. Originální popis (LLM):"
        else -> "Připojení **$connectionName**:"
    }
    return buildString {
        appendLine(head)
        if (req.message.isNotBlank()) {
            appendLine()
            appendLine(req.message)
        }
        req.preview?.takeIf { it.isNotBlank() }?.let {
            appendLine()
            appendLine("> $it")
        }
        req.screenshot?.takeIf { it.isNotBlank() }?.let {
            appendLine()
            appendLine("[screenshot]($it)")
        }
    }.trim()
}

/**
 * Internal REST endpoint for kind-aware notifications from the browser pool.
 *
 * Direct messages and meeting invites, MFA, auth requests, and hard errors all
 * flow through here. urgent_message is deduplicated per (connectionId, chatId)
 * in a 60s window so a persistently unread chat does not spam push.
 */
fun Routing.installInternalO365NotifyApi(
    connectionRepository: ConnectionRepository,
    taskRepository: TaskRepository,
    notificationRpc: NotificationRpcImpl,
    fcmPushService: FcmPushService,
    apnsPushService: ApnsPushService,
    deviceTokenRepository: DeviceTokenRepository? = null,
) {
    post("/internal/o365/notify") {
        try {
            val body = call.receive<O365NotifyRequest>()
            val connectionId = try {
                ConnectionId(ObjectId(body.connectionId))
            } catch (_: Exception) {
                call.respondText(
                    """{"error":"invalid connectionId"}""",
                    ContentType.Application.Json, HttpStatusCode.BadRequest,
                )
                return@post
            }
            val connection = connectionRepository.getById(connectionId) ?: run {
                call.respondText(
                    """{"error":"connection not found"}""",
                    ContentType.Application.Json, HttpStatusCode.NotFound,
                )
                return@post
            }

            if (body.kind == "urgent_message" && body.chatId != null) {
                val key = "${body.connectionId}|${body.chatId}"
                val now = System.currentTimeMillis()
                val last = urgentDedupCache[key]
                if (last != null && now - last < URGENT_DEDUP_WINDOW_MS) {
                    logger.debug { "Deduped urgent_message for $key" }
                    call.respondText(
                        """{"status":"deduped"}""",
                        ContentType.Application.Json, HttpStatusCode.OK,
                    )
                    return@post
                }
                urgentDedupCache[key] = now
            }

            // Alone-check suppression (product §10a) — if the user already
            // answered "Zůstat", swallow further alone_check pushes until
            // the window expires.
            if (body.kind == "meeting_alone_check" && body.meetingId != null &&
                isAloneSuppressed(body.meetingId)
            ) {
                logger.debug { "Suppressed meeting_alone_check for ${body.meetingId}" }
                call.respondText(
                    """{"status":"suppressed"}""",
                    ContentType.Application.Json, HttpStatusCode.OK,
                )
                return@post
            }

            val clientIds = try {
                deviceTokenRepository?.findAll()?.toList()
                    ?.map { it.clientId }?.distinct()
                    ?.map { ClientId(ObjectId(it)) }
                    ?: listOf(ClientId(ObjectId("68a332361b04695a243e5ae8")))
            } catch (_: Exception) {
                listOf(ClientId(ObjectId("68a332361b04695a243e5ae8")))
            }

            val title = titleFor(body.kind, body, connection.name)
            val description = czechDescription(body.kind, body, connection.name)
            val priority = priorityFor(body.kind)
            val alwaysPush = alwaysPushFor(body.kind)

            for (cid in clientIds) {
                val hasActiveUi = if (alwaysPush) false else notificationRpc.hasActiveSubscribers(cid.toString())
                val task = TaskDocument(
                    clientId = cid,
                    taskName = title,
                    content = description,
                    state = TaskStateEnum.USER_TASK,
                    type = TaskTypeEnum.SYSTEM,
                    sourceUrn = SourceUrn("o365-browser-pool::event:${body.kind}"),
                    pendingUserQuestion = title,
                    userQuestionContext = description,
                    priorityScore = priority,
                    lastActivityAt = Instant.now(),
                    actionType = body.connectionId,
                )
                taskRepository.save(task)

                notificationRpc.emitUserTaskCreated(
                    clientId = cid.toString(),
                    taskId = task.id.toString(),
                    title = title,
                    interruptAction = body.kind,
                    interruptDescription = description,
                    connectionName = connection.name,
                )

                if (!hasActiveUi) {
                    val pushData = buildMap {
                        put("taskId", task.id.toString())
                        put("type", "user_task")
                        put("interruptAction", body.kind)
                        body.chatId?.let { put("chatId", it) }
                        body.chatName?.let { put("chatName", it) }
                        body.mfaCode?.takeIf { it.isNotBlank() }?.let { put("mfaCode", it) }
                        body.meetingId?.takeIf { it.isNotBlank() }?.let { put("meetingId", it) }
                    }
                    try {
                        fcmPushService.sendPushNotification(cid.toString(), "Microsoft 365", title, pushData)
                    } catch (e: Exception) {
                        logger.warn { "FCM push failed for $cid: ${e.message}" }
                    }
                    try {
                        apnsPushService.sendPushNotification(cid.toString(), "Microsoft 365", title, pushData)
                    } catch (e: Exception) {
                        logger.warn { "APNs push failed for $cid: ${e.message}" }
                    }
                }
            }

            call.respondText(
                """{"status":"ok","kind":"${body.kind}","priority":$priority}""",
                ContentType.Application.Json, HttpStatusCode.OK,
            )
        } catch (e: Exception) {
            logger.error(e) { "Error handling O365 notify" }
            call.respondText(
                json.encodeToString(mapOf("error" to (e.message ?: "internal error"))),
                ContentType.Application.Json, HttpStatusCode.InternalServerError,
            )
        }
    }
}
