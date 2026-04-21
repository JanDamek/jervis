package com.jervis.infrastructure.grpc

import com.jervis.common.types.ClientId
import com.jervis.common.types.ConnectionId
import com.jervis.common.types.SourceUrn
import com.jervis.connection.ConnectionRepository
import com.jervis.contracts.server.CapabilitiesDiscoveredRequest
import com.jervis.contracts.server.CapabilitiesDiscoveredResponse
import com.jervis.contracts.server.NotifyRequest
import com.jervis.contracts.server.NotifyResponse
import com.jervis.contracts.server.ServerO365SessionServiceGrpcKt
import com.jervis.contracts.server.SessionEventRequest
import com.jervis.contracts.server.SessionEventResponse
import com.jervis.dto.connection.ConnectionCapability
import com.jervis.dto.connection.ConnectionStateEnum
import com.jervis.dto.task.TaskStateEnum
import com.jervis.dto.task.TaskTypeEnum
import com.jervis.infrastructure.notification.ApnsPushService
import com.jervis.infrastructure.notification.FcmPushService
import com.jervis.meeting.isAloneSuppressed
import com.jervis.preferences.DeviceTokenRepository
import com.jervis.rpc.NotificationRpcImpl
import com.jervis.task.TaskDocument
import com.jervis.task.TaskRepository
import kotlinx.coroutines.flow.toList
import mu.KotlinLogging
import org.bson.types.ObjectId
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

@Component
class ServerO365SessionGrpcImpl(
    private val connectionRepository: ConnectionRepository,
    private val taskRepository: TaskRepository,
    private val notificationRpc: NotificationRpcImpl,
    private val fcmPushService: FcmPushService,
    private val apnsPushService: ApnsPushService,
    private val deviceTokenRepository: DeviceTokenRepository? = null,
) : ServerO365SessionServiceGrpcKt.ServerO365SessionServiceCoroutineImplBase() {
    private val logger = KotlinLogging.logger {}

    // 60s dedup window for urgent_message, keyed by (connectionId, chatId).
    private val urgentDedupCache: MutableMap<String, Long> = ConcurrentHashMap()
    private val URGENT_DEDUP_WINDOW_MS = 60_000L

    override suspend fun sessionEvent(request: SessionEventRequest): SessionEventResponse {
        logger.info { "O365 session event: state=${request.state} connection=${request.connectionId}" }
        val connectionId = try {
            ConnectionId(ObjectId(request.connectionId))
        } catch (_: Exception) {
            logger.warn { "Invalid connectionId: ${request.connectionId}" }
            return SessionEventResponse.newBuilder().setStatus("error:invalid_connection_id").build()
        }
        val connection = connectionRepository.getById(connectionId) ?: run {
            logger.warn { "Connection not found: ${request.connectionId}" }
            return SessionEventResponse.newBuilder().setStatus("error:connection_not_found").build()
        }
        val clientIds = resolveClientIds()

        when (request.state) {
            "AWAITING_MFA" -> {
                // MFA is ephemeral — the user approves a number (or types a
                // code) on their phone and the pod self-heals into ACTIVE.
                // Server has nothing to react to, so don't write a
                // TaskDocument / emitUserTaskCreated / pollute the chat UI.
                // Push straight to FCM + APNs and move on.
                val title = buildString {
                    append("Microsoft 365: ")
                    when (request.mfaType) {
                        "authenticator_number" ->
                            append("Potvrďte číslo ${request.mfaNumber.takeIf { it.isNotBlank() } ?: ""} v Authenticator")
                        "authenticator_code" -> append("Zadejte kód z Authenticator")
                        "sms_code" -> append("Zadejte SMS kód")
                        "phone_call" -> append("Potvrďte telefonní hovor")
                        else -> append("Vyžadováno dvoufaktorové ověření")
                    }
                }
                val body = buildString {
                    request.mfaNumber.takeIf { it.isNotBlank() }?.let { append("Číslo: $it — ") }
                    append("Připojení ${connection.name}")
                }
                for (cid in clientIds) {
                    sendMfaPushDirect(
                        clientId = cid,
                        title = title,
                        body = body,
                        mfaType = request.mfaType.takeIf { it.isNotBlank() },
                        mfaNumber = request.mfaNumber.takeIf { it.isNotBlank() },
                    )
                }
            }
            "EXPIRED" -> {
                logger.info {
                    "Session EXPIRED for ${connection.name} (${request.connectionId}) — pod self-heals, no user notification"
                }
            }
            else -> logger.debug { "Ignoring session event state=${request.state} for ${request.connectionId}" }
        }
        return SessionEventResponse.newBuilder().setStatus("ok").build()
    }

    override suspend fun capabilitiesDiscovered(
        request: CapabilitiesDiscoveredRequest,
    ): CapabilitiesDiscoveredResponse {
        logger.info { "O365 capabilities discovered for ${request.connectionId}: ${request.availableCapabilitiesList}" }
        val connectionId = try {
            ConnectionId(ObjectId(request.connectionId))
        } catch (_: Exception) {
            return CapabilitiesDiscoveredResponse.newBuilder().setOk(false)
                .setError("invalid connectionId").build()
        }
        val connection = connectionRepository.getById(connectionId)
            ?: return CapabilitiesDiscoveredResponse.newBuilder().setOk(false)
                .setError("connection not found").build()
        val capabilities = request.availableCapabilitiesList.mapNotNull { name ->
            try {
                ConnectionCapability.valueOf(name)
            } catch (_: IllegalArgumentException) {
                logger.warn { "Unknown capability: $name" }
                null
            }
        }.toSet()
        connectionRepository.save(
            connection.copy(
                availableCapabilities = capabilities,
                state = ConnectionStateEnum.VALID,
            ),
        )
        logger.info { "Connection ${request.connectionId} capabilities updated: $capabilities (state → VALID)" }

        val clientId = ClientId(ObjectId("68a332361b04695a243e5ae8"))
        val capLabels = capabilities.joinToString(", ") { it.name }
        val message = if (capabilities.isEmpty()) {
            "Připojení ${connection.name}: žádné služby nenalezeny"
        } else {
            "Připojení ${connection.name}: nalezeny služby — $capLabels"
        }
        notificationRpc.emitConnectionStateChanged(
            clientId = clientId.toString(),
            connectionId = request.connectionId,
            connectionName = connection.name,
            newState = "VALID",
            message = message,
        )
        if (!notificationRpc.hasActiveSubscribers(clientId.toString())) {
            try {
                fcmPushService.sendPushNotification(clientId.toString(), "Microsoft 365", message, emptyMap())
            } catch (e: Exception) {
                logger.warn { "FCM push failed for discovery: ${e.message}" }
            }
            try {
                apnsPushService.sendPushNotification(clientId.toString(), "Microsoft 365", message, emptyMap())
            } catch (e: Exception) {
                logger.warn { "APNs push failed for discovery: ${e.message}" }
            }
        }
        return CapabilitiesDiscoveredResponse.newBuilder().setOk(true).setCapabilities(capabilities.size).build()
    }

    override suspend fun notify(request: NotifyRequest): NotifyResponse {
        val connectionId = try {
            ConnectionId(ObjectId(request.connectionId))
        } catch (_: Exception) {
            return NotifyResponse.newBuilder().setStatus("error:invalid_connection_id").setKind(request.kind).build()
        }
        val connection = connectionRepository.getById(connectionId)
            ?: return NotifyResponse.newBuilder().setStatus("error:connection_not_found").setKind(request.kind).build()

        if (request.kind == "urgent_message" && request.chatId.isNotBlank()) {
            val key = "${request.connectionId}|${request.chatId}"
            val now = System.currentTimeMillis()
            val last = urgentDedupCache[key]
            if (last != null && now - last < URGENT_DEDUP_WINDOW_MS) {
                logger.debug { "Deduped urgent_message for $key" }
                return NotifyResponse.newBuilder().setStatus("deduped").setKind(request.kind).build()
            }
            urgentDedupCache[key] = now
        }

        if (request.kind == "meeting_alone_check" && request.meetingId.isNotBlank() &&
            isAloneSuppressed(request.meetingId)
        ) {
            logger.debug { "Suppressed meeting_alone_check for ${request.meetingId}" }
            return NotifyResponse.newBuilder().setStatus("suppressed").setKind(request.kind).build()
        }

        val clientIds = resolveClientIds()
        val title = titleFor(request, connection.name)
        val description = czechDescription(request, connection.name)
        val priority = priorityFor(request.kind)
        val alwaysPush = alwaysPushFor(request.kind)

        // MFA short-circuit: the server has nothing to "know" about an
        // MFA challenge — the pod handles the flow end-to-end once the
        // user approves on their phone. Skip TaskDocument.save /
        // emitUserTaskCreated for this kind so the chat UI doesn't fill
        // with ephemeral "Potvrďte číslo 68" rows that get superseded
        // seconds later. Direct push only.
        if (request.kind == "mfa") {
            for (cid in clientIds) {
                sendMfaPushDirect(
                    clientId = cid,
                    title = title,
                    body = description,
                    mfaType = null,
                    mfaNumber = request.mfaCode.takeIf { it.isNotBlank() },
                )
            }
            return NotifyResponse.newBuilder()
                .setStatus("ok").setKind(request.kind).setPriority(priority).build()
        }

        for (cid in clientIds) {
            val hasActiveUi = if (alwaysPush) false else notificationRpc.hasActiveSubscribers(cid.toString())
            val task = TaskDocument(
                clientId = cid,
                taskName = title,
                content = description,
                state = TaskStateEnum.USER_TASK,
                type = TaskTypeEnum.SYSTEM,
                sourceUrn = SourceUrn("o365-browser-pool::event:${request.kind}"),
                pendingUserQuestion = title,
                userQuestionContext = description,
                priorityScore = priority,
                lastActivityAt = Instant.now(),
                actionType = request.connectionId,
            )
            taskRepository.save(task)

            notificationRpc.emitUserTaskCreated(
                clientId = cid.toString(),
                taskId = task.id.toString(),
                title = title,
                interruptAction = request.kind,
                interruptDescription = description,
                connectionName = connection.name,
            )

            if (!hasActiveUi) {
                val pushData = buildMap {
                    put("taskId", task.id.toString())
                    put("type", "user_task")
                    put("interruptAction", request.kind)
                    request.chatId.takeIf { it.isNotBlank() }?.let { put("chatId", it) }
                    request.chatName.takeIf { it.isNotBlank() }?.let { put("chatName", it) }
                    request.mfaCode.takeIf { it.isNotBlank() }?.let { put("mfaCode", it) }
                    request.meetingId.takeIf { it.isNotBlank() }?.let { put("meetingId", it) }
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

        return NotifyResponse.newBuilder().setStatus("ok").setKind(request.kind).setPriority(priority).build()
    }

    // ── Helpers ──

    private suspend fun resolveClientIds(): List<ClientId> = try {
        deviceTokenRepository?.findAll()?.toList()
            ?.map { it.clientId }?.distinct()
            ?.map { ClientId(ObjectId(it)) }
            ?: listOf(ClientId(ObjectId("68a332361b04695a243e5ae8")))
    } catch (_: Exception) {
        listOf(ClientId(ObjectId("68a332361b04695a243e5ae8")))
    }

    /**
     * MFA fast path: send FCM + APNs push straight to the user's devices
     * without creating a TaskDocument or emitting a `userTaskCreated`
     * event. MFA is a transient challenge — the pod resolves it the
     * moment the user approves on the Authenticator app, so there is no
     * ongoing task for the server to track, reply to, or surface in the
     * chat UI. Everything else (urgent_message, meeting_invite, …) still
     * goes through the full `notify` path so the server has context for
     * follow-up replies.
     */
    private suspend fun sendMfaPushDirect(
        clientId: ClientId,
        title: String,
        body: String,
        mfaType: String?,
        mfaNumber: String?,
    ) {
        val pushData = buildMap {
            put("type", "mfa")
            put("interruptAction", "o365_mfa")
            mfaType?.let { put("mfaType", it) }
            mfaNumber?.let { put("mfaNumber", it) }
        }
        try {
            fcmPushService.sendPushNotification(clientId.toString(), title, body, pushData)
        } catch (e: Exception) {
            logger.warn { "FCM MFA push failed for $clientId: ${e.message}" }
        }
        try {
            apnsPushService.sendPushNotification(clientId.toString(), title, body, pushData)
        } catch (e: Exception) {
            logger.warn { "APNs MFA push failed for $clientId: ${e.message}" }
        }
        logger.info { "MFA push sent to $clientId: $title" }
    }

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

    private fun titleFor(req: NotifyRequest, connectionName: String): String = when (req.kind) {
        "urgent_message" -> "[$connectionName] Direct od ${req.sender.takeIf { it.isNotBlank() } ?: req.chatName.takeIf { it.isNotBlank() } ?: "?"}"
        "meeting_invite" -> "[$connectionName] Schůzka: ${req.chatName.takeIf { it.isNotBlank() } ?: "příchozí hovor"}"
        "meeting_alone_check" -> "[$connectionName] V meetingu jsi sám — odejít?"
        "auth_request" -> "[$connectionName] Povolit přihlášení?"
        "mfa" -> {
            val code = req.mfaCode.takeIf { it.isNotBlank() }
            if (code != null) "[$connectionName] Potvrď $code v Authenticatoru"
            else "[$connectionName] Dvoufaktorové ověření"
        }
        "error" -> "[$connectionName] Agent uvízl — potřebuje pomoc"
        else -> "[$connectionName] ${req.message.take(60)}"
    }

    private fun czechDescription(req: NotifyRequest, connectionName: String): String {
        val head = when (req.kind) {
            "urgent_message" -> "Nová přímá zpráva v připojení **$connectionName** od **${req.sender.takeIf { it.isNotBlank() } ?: req.chatName.takeIf { it.isNotBlank() } ?: "?"}**."
            "meeting_invite" -> "V připojení **$connectionName** zvoní hovor${req.chatName.takeIf { it.isNotBlank() }?.let { " v chatu **$it**" } ?: ""}. Mám se připojit a nahrát meeting?"
            "meeting_alone_check" -> "V meetingu jsi sám — mám z něj Jervise odhlásit, nebo ještě počkat?"
            "auth_request" -> "Připojení **$connectionName** potřebuje mimo pracovní dobu schválit přihlášení."
            "mfa" -> {
                val code = req.mfaCode.takeIf { it.isNotBlank() }
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
            req.preview.takeIf { it.isNotBlank() }?.let {
                appendLine()
                appendLine("> $it")
            }
            req.screenshot.takeIf { it.isNotBlank() }?.let {
                appendLine()
                appendLine("[screenshot]($it)")
            }
        }.trim()
    }
}
