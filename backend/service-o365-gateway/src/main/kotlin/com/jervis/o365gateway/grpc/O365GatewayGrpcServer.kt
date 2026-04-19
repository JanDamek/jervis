package com.jervis.o365gateway.grpc

import com.google.protobuf.ByteString
import com.jervis.contracts.interceptors.ServerContextInterceptor
import com.jervis.contracts.o365_gateway.Attendee as ProtoAttendee
import com.jervis.contracts.o365_gateway.CalendarEvent as ProtoCalendarEvent
import com.jervis.contracts.o365_gateway.Channel as ProtoChannel
import com.jervis.contracts.o365_gateway.ChatMessage as ProtoChatMessage
import com.jervis.contracts.o365_gateway.ChatSummary as ProtoChatSummary
import com.jervis.contracts.o365_gateway.CreateCalendarEventRequest
import com.jervis.contracts.o365_gateway.DateTimeTimeZone as ProtoDateTimeTimeZone
import com.jervis.contracts.o365_gateway.EmailAddress as ProtoEmailAddress
import com.jervis.contracts.o365_gateway.GraphApplication as ProtoGraphApplication
import com.jervis.contracts.o365_gateway.GraphUser as ProtoGraphUser
import com.jervis.contracts.o365_gateway.ListCalendarEventsRequest
import com.jervis.contracts.o365_gateway.ListCalendarEventsResponse
import com.jervis.contracts.o365_gateway.Location as ProtoLocation
import com.jervis.contracts.o365_gateway.ListChannelMessagesResponse
import com.jervis.contracts.o365_gateway.ListChannelsRequest
import com.jervis.contracts.o365_gateway.ListChannelsResponse
import com.jervis.contracts.o365_gateway.ListChatMessagesResponse
import com.jervis.contracts.o365_gateway.ListChatsRequest
import com.jervis.contracts.o365_gateway.ListChatsResponse
import com.jervis.contracts.o365_gateway.ListMailRequest
import com.jervis.contracts.o365_gateway.ListMailResponse
import com.jervis.contracts.o365_gateway.ListTeamsRequest
import com.jervis.contracts.o365_gateway.ListTeamsResponse
import com.jervis.contracts.o365_gateway.MailBody as ProtoMailBody
import com.jervis.contracts.o365_gateway.MailMessage as ProtoMailMessage
import com.jervis.contracts.o365_gateway.MailSender as ProtoMailSender
import com.jervis.contracts.o365_gateway.MessageBody as ProtoMessageBody
import com.jervis.contracts.o365_gateway.MessageFrom as ProtoMessageFrom
import com.jervis.contracts.o365_gateway.MessagePreview as ProtoMessagePreview
import com.jervis.contracts.o365_gateway.O365BytesResponse
import com.jervis.contracts.o365_gateway.O365GatewayServiceGrpcKt
import com.jervis.contracts.o365_gateway.O365Request
import com.jervis.contracts.o365_gateway.O365Response
import com.jervis.contracts.o365_gateway.ReadChannelRequest
import com.jervis.contracts.o365_gateway.ReadChatRequest
import com.jervis.contracts.o365_gateway.ReadMailRequest
import com.jervis.contracts.o365_gateway.Recipient as ProtoRecipient
import com.jervis.contracts.o365_gateway.SendChannelMessageRequest
import com.jervis.contracts.o365_gateway.SendChatMessageRequest
import com.jervis.contracts.o365_gateway.SendMailAck
import com.jervis.contracts.o365_gateway.SendMailRpcRequest
import com.jervis.contracts.o365_gateway.Team as ProtoTeam
import com.jervis.o365gateway.model.CreateEventRequest
import com.jervis.o365gateway.model.GraphApplication
import com.jervis.o365gateway.model.GraphChannel
import com.jervis.o365gateway.model.GraphChat
import com.jervis.o365gateway.model.GraphAttendee
import com.jervis.o365gateway.model.GraphDateTimeTimeZone
import com.jervis.o365gateway.model.GraphEmailAddress
import com.jervis.o365gateway.model.GraphEmailAddressDetail
import com.jervis.o365gateway.model.GraphEvent
import com.jervis.o365gateway.model.GraphLocation
import com.jervis.o365gateway.model.GraphMailBody
import com.jervis.o365gateway.model.GraphMailMessage
import com.jervis.o365gateway.model.GraphMessage
import com.jervis.o365gateway.model.GraphMessageBody
import com.jervis.o365gateway.model.GraphMessageFrom
import com.jervis.o365gateway.model.GraphMessagePreview
import com.jervis.o365gateway.model.GraphRecipient
import com.jervis.o365gateway.model.GraphTeam
import com.jervis.o365gateway.model.GraphUser
import com.jervis.o365gateway.model.SendMailMessage
import com.jervis.o365gateway.model.SendMailRequest
import com.jervis.o365gateway.service.BrowserPoolClient
import com.jervis.o365gateway.service.GraphApiService
import io.grpc.Server
import io.grpc.ServerInterceptors
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder
import io.grpc.protobuf.services.ProtoReflectionServiceV1
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.serializer
import mu.KotlinLogging
import java.util.concurrent.TimeUnit

private val logger = KotlinLogging.logger {}

/**
 * gRPC server for the O365 Gateway.
 *
 * Passthrough design: one RPC Request(path, method, query, body) routes to
 * the matching GraphApiService / BrowserPoolClient call. Response bodies
 * ride back as JSON text so orchestrator tools can keep the Graph-native
 * parsing they already have.
 *
 * Routing mirrors the retired Ktor /api/o365/* routes 1:1 — if you add
 * a Graph endpoint, wire it both here (gRPC) and in GraphApiService.
 */
class O365GatewayGrpcServer(
    private val graphApi: GraphApiService,
    private val browserPool: BrowserPoolClient,
    private val port: Int = 5501,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }
    private var server: Server? = null

    fun start() {
        val interceptor = ServerContextInterceptor()
        val servicer = GatewayServicer(graphApi, browserPool, json)
        val builder = NettyServerBuilder.forPort(port)
            .maxInboundMessageSize(64 * 1024 * 1024)
            .addService(ServerInterceptors.intercept(servicer, interceptor))
            .addService(ProtoReflectionServiceV1.newInstance())
        server = builder.build().also { it.start() }
        logger.info { "O365 Gateway gRPC listening on :$port" }
    }

    fun stop() {
        runCatching {
            server?.shutdown()?.awaitTermination(5, TimeUnit.SECONDS)
            server?.shutdownNow()
        }
    }
}

private class GatewayServicer(
    private val graphApi: GraphApiService,
    private val browserPool: BrowserPoolClient,
    private val json: Json,
) : O365GatewayServiceGrpcKt.O365GatewayServiceCoroutineImplBase() {

    override suspend fun request(request: O365Request): O365Response {
        val path = request.path.trim().trim('/')
        val method = request.method.ifBlank { "GET" }.uppercase()
        val query = request.queryMap
        val bodyJson = request.bodyJson

        return runCatching {
            val parts = path.split('/')
            val element: JsonElement = dispatch(method, parts, query, bodyJson)
            O365Response.newBuilder()
                .setStatusCode(200)
                .setBodyJson(json.encodeToString(JsonElement.serializer(), element))
                .build()
        }.getOrElse { e ->
            logger.warn(e) { "O365_GRPC_REQ_FAIL | method=$method path=$path" }
            val status = if (e is NoSuchElementException) 404 else 502
            O365Response.newBuilder()
                .setStatusCode(status)
                .setBodyJson("""{"error":"${e.message?.replace("\"", "'")?.take(300)}"}""")
                .build()
        }
    }

    // === V5a - Teams chats typed =============================================

    override suspend fun listChats(request: ListChatsRequest): ListChatsResponse {
        val top = (if (request.top == 0) 20 else request.top).coerceIn(1, 50)
        val chats = graphApi.listChats(request.clientId, top)
        return ListChatsResponse.newBuilder()
            .apply { chats.forEach { addChats(it.toProto()) } }
            .build()
    }

    override suspend fun readChat(request: ReadChatRequest): ListChatMessagesResponse {
        val top = if (request.top == 0) 20 else request.top
        val messages = graphApi.readChat(request.clientId, request.chatId, top)
        return ListChatMessagesResponse.newBuilder()
            .apply { messages.forEach { addMessages(it.toProto()) } }
            .build()
    }

    override suspend fun sendChatMessage(request: SendChatMessageRequest): ProtoChatMessage {
        val ct = request.contentType.ifBlank { "text" }
        val result = graphApi.sendChatMessage(request.clientId, request.chatId, request.content, ct)
        return result.toProto()
    }

    // === V5b - Teams teams/channels typed ====================================

    override suspend fun listTeams(request: ListTeamsRequest): ListTeamsResponse {
        val teams = graphApi.listTeams(request.clientId)
        return ListTeamsResponse.newBuilder()
            .apply { teams.forEach { addTeams(it.toProto()) } }
            .build()
    }

    override suspend fun listChannels(request: ListChannelsRequest): ListChannelsResponse {
        val channels = graphApi.listChannels(request.clientId, request.teamId)
        return ListChannelsResponse.newBuilder()
            .apply { channels.forEach { addChannels(it.toProto()) } }
            .build()
    }

    override suspend fun readChannel(request: ReadChannelRequest): ListChannelMessagesResponse {
        val top = if (request.top == 0) 20 else request.top
        val messages = graphApi.readChannel(request.clientId, request.teamId, request.channelId, top)
        return ListChannelMessagesResponse.newBuilder()
            .apply { messages.forEach { addMessages(it.toProto()) } }
            .build()
    }

    override suspend fun sendChannelMessage(request: SendChannelMessageRequest): ProtoChatMessage {
        val result = graphApi.sendChannelMessage(
            request.clientId, request.teamId, request.channelId, request.content,
        )
        return result.toProto()
    }

    // === V5c - Mail (Outlook) typed ==========================================

    override suspend fun listMail(request: ListMailRequest): ListMailResponse {
        val top = if (request.top == 0) 20 else request.top
        val folder = request.folder.ifBlank { "inbox" }
        val filter = request.filter.takeIf { it.isNotBlank() }
        val messages = graphApi.listMail(request.clientId, top, folder, filter)
        return ListMailResponse.newBuilder()
            .apply { messages.forEach { addMessages(it.toProto()) } }
            .build()
    }

    override suspend fun readMail(request: ReadMailRequest): ProtoMailMessage {
        val m = graphApi.readMail(request.clientId, request.messageId)
        return m.toProto()
    }

    override suspend fun sendMail(request: SendMailRpcRequest): SendMailAck {
        val mail = SendMailRequest(
            message = SendMailMessage(
                subject = request.subject,
                body = GraphMailBody(
                    contentType = request.body.contentType.ifBlank { "text" },
                    content = request.body.content,
                ),
                toRecipients = request.toRecipientsList.map { it.toGraph() },
                ccRecipients = request.ccRecipientsList.map { it.toGraph() },
            ),
            saveToSentItems = request.saveToSentItems,
        )
        graphApi.sendMail(request.clientId, mail)
        return SendMailAck.newBuilder().setResult("Mail sent successfully").build()
    }

    // === V5d - Calendar typed ================================================

    override suspend fun listCalendarEvents(
        request: ListCalendarEventsRequest,
    ): ListCalendarEventsResponse {
        val top = if (request.top == 0) 20 else request.top
        val events = graphApi.listEvents(
            request.clientId,
            top,
            request.startDateTime.takeIf { it.isNotBlank() },
            request.endDateTime.takeIf { it.isNotBlank() },
        )
        return ListCalendarEventsResponse.newBuilder()
            .apply { events.forEach { addEvents(it.toProto()) } }
            .build()
    }

    override suspend fun createCalendarEvent(
        request: CreateCalendarEventRequest,
    ): ProtoCalendarEvent {
        val createReq = CreateEventRequest(
            subject = request.subject,
            body = if (request.hasBody()) GraphMailBody(
                contentType = request.body.contentType.ifBlank { "text" },
                content = request.body.content,
            ) else null,
            start = request.start.toGraph(),
            end = request.end.toGraph(),
            location = if (request.hasLocation()) GraphLocation(
                displayName = request.location.displayName.takeIf { it.isNotBlank() },
            ) else null,
            attendees = request.attendeesList.takeIf { it.isNotEmpty() }?.map { it.toGraph() },
            isOnlineMeeting = request.isOnlineMeeting,
        )
        return graphApi.createEvent(request.clientId, createReq).toProto()
    }

    override suspend fun requestBytes(request: O365Request): O365BytesResponse {
        val path = request.path.trim().trim('/')
        val parts = path.split('/')
        return runCatching {
            when {
                // online-meetings/{clientId}/{meetingId}/transcripts/{transcriptId}/content
                parts.size == 5 && parts[0] == "online-meetings" && parts[3] == "transcripts" && parts[4] == "content" -> {
                    val vtt = graphApi.downloadTranscriptVtt(parts[1], parts[2], parts[3])
                    O365BytesResponse.newBuilder()
                        .setStatusCode(200)
                        .setBody(ByteString.copyFrom(vtt))
                        .setContentType("text/vtt")
                        .build()
                }
                else -> {
                    O365BytesResponse.newBuilder()
                        .setStatusCode(404)
                        .setContentType("text/plain")
                        .setBody(ByteString.copyFromUtf8("No binary route for $path"))
                        .build()
                }
            }
        }.getOrElse { e ->
            logger.warn(e) { "O365_GRPC_REQ_BYTES_FAIL | path=$path" }
            O365BytesResponse.newBuilder()
                .setStatusCode(502)
                .setContentType("text/plain")
                .setBody(ByteString.copyFromUtf8(e.message.orEmpty()))
                .build()
        }
    }

    private suspend fun dispatch(
        method: String,
        parts: List<String>,
        query: Map<String, String>,
        bodyJson: String,
    ): JsonElement {
        val top = query["top"]?.toIntOrNull()
        return when {
            // chats/{clientId}
            method == "GET" && parts.size == 2 && parts[0] == "chats" ->
                encode(graphApi.listChats(parts[1], (top ?: 20).coerceIn(1, 50)))

            // chats/{clientId}/{chatId}/messages
            method == "GET" && parts.size == 4 && parts[0] == "chats" && parts[3] == "messages" ->
                encode(graphApi.readChat(parts[1], parts[2], top ?: 20))
            method == "POST" && parts.size == 4 && parts[0] == "chats" && parts[3] == "messages" -> {
                val body = json.decodeFromString<GraphMessageBody>(bodyJson.ifBlank { "{}" })
                encode(graphApi.sendChatMessage(parts[1], parts[2], body.content ?: "", body.contentType ?: "text"))
            }

            // teams/{clientId}
            method == "GET" && parts.size == 2 && parts[0] == "teams" ->
                encode(graphApi.listTeams(parts[1]))
            // teams/{clientId}/{teamId}/channels
            method == "GET" && parts.size == 4 && parts[0] == "teams" && parts[3] == "channels" ->
                encode(graphApi.listChannels(parts[1], parts[2]))
            // teams/{clientId}/{teamId}/channels/{channelId}/messages
            method == "GET" && parts.size == 6 && parts[0] == "teams" && parts[3] == "channels" && parts[5] == "messages" ->
                encode(graphApi.readChannel(parts[1], parts[2], parts[4], top ?: 20))
            method == "POST" && parts.size == 6 && parts[0] == "teams" && parts[3] == "channels" && parts[5] == "messages" -> {
                val body = json.decodeFromString<GraphMessageBody>(bodyJson.ifBlank { "{}" })
                encode(graphApi.sendChannelMessage(parts[1], parts[2], parts[4], body.content ?: ""))
            }

            // mail/{clientId}
            method == "GET" && parts.size == 2 && parts[0] == "mail" ->
                encode(graphApi.listMail(parts[1], top ?: 20, query["folder"] ?: "inbox", query["filter"]))
            // mail/{clientId}/{messageId}
            method == "GET" && parts.size == 3 && parts[0] == "mail" ->
                encode(graphApi.readMail(parts[1], parts[2]))
            // mail/{clientId}/send
            method == "POST" && parts.size == 3 && parts[0] == "mail" && parts[2] == "send" -> {
                val body = json.decodeFromString<SendMailRequest>(bodyJson.ifBlank { "{}" })
                graphApi.sendMail(parts[1], body)
                json.encodeToJsonElement(mapOf("result" to "Mail sent successfully"))
            }

            // calendar/{clientId}
            method == "GET" && parts.size == 2 && parts[0] == "calendar" ->
                encode(
                    graphApi.listEvents(
                        parts[1], top ?: 20, query["startDateTime"], query["endDateTime"],
                    ),
                )
            method == "POST" && parts.size == 2 && parts[0] == "calendar" -> {
                val body = json.decodeFromString<CreateEventRequest>(bodyJson.ifBlank { "{}" })
                encode(graphApi.createEvent(parts[1], body))
            }

            // online-meetings/{clientId}/by-join-url
            method == "GET" && parts.size == 3 && parts[0] == "online-meetings" && parts[2] == "by-join-url" -> {
                val joinUrl = query["joinUrl"] ?: throw IllegalArgumentException("Missing joinUrl query param")
                val meeting = graphApi.getOnlineMeetingByJoinUrl(parts[1], joinUrl)
                    ?: throw NoSuchElementException("No meeting matches joinUrl")
                encode(meeting)
            }
            // online-meetings/{clientId}/{meetingId}
            method == "GET" && parts.size == 3 && parts[0] == "online-meetings" ->
                encode(graphApi.getOnlineMeeting(parts[1], parts[2]))
            // online-meetings/{clientId}/{meetingId}/recordings
            method == "GET" && parts.size == 4 && parts[0] == "online-meetings" && parts[3] == "recordings" ->
                encode(graphApi.listMeetingRecordings(parts[1], parts[2]))
            // online-meetings/{clientId}/{meetingId}/transcripts
            method == "GET" && parts.size == 4 && parts[0] == "online-meetings" && parts[3] == "transcripts" ->
                encode(graphApi.listMeetingTranscripts(parts[1], parts[2]))

            // drive/{clientId}
            method == "GET" && parts.size == 2 && parts[0] == "drive" ->
                encode(graphApi.listDriveItems(parts[1], query["path"] ?: "root", top ?: 50))
            // drive/{clientId}/item/{itemId}
            method == "GET" && parts.size == 4 && parts[0] == "drive" && parts[2] == "item" ->
                encode(graphApi.getDriveItem(parts[1], parts[3]))
            // drive/{clientId}/search
            method == "GET" && parts.size == 3 && parts[0] == "drive" && parts[2] == "search" -> {
                val q = query["q"] ?: throw IllegalArgumentException("Missing q query param")
                encode(graphApi.searchDrive(parts[1], q, top ?: 25))
            }

            // session/{clientId}
            method == "GET" && parts.size == 2 && parts[0] == "session" -> {
                val status = browserPool.getSessionStatus(parts[1])
                    ?: throw NoSuchElementException("No session for client '${parts[1]}'")
                encode(status)
            }

            else -> throw IllegalArgumentException("Unsupported route: $method /${parts.joinToString("/")}")
        }
    }

    private inline fun <reified T> encode(value: T): JsonElement =
        json.encodeToJsonElement(serializer(), value)
}

// === V5a - Teams chats: Graph DTO -> proto mappers ============================

private fun GraphUser.toProto(): ProtoGraphUser =
    ProtoGraphUser.newBuilder()
        .setId(id.orEmpty())
        .setDisplayName(displayName.orEmpty())
        .build()

private fun GraphApplication.toProto(): ProtoGraphApplication =
    ProtoGraphApplication.newBuilder()
        .setId(id.orEmpty())
        .setDisplayName(displayName.orEmpty())
        .build()

private fun GraphMessageBody.toProto(): ProtoMessageBody =
    ProtoMessageBody.newBuilder()
        .setContentType(contentType.orEmpty())
        .setContent(content.orEmpty())
        .build()

private fun GraphMessageFrom.toProto(): ProtoMessageFrom =
    ProtoMessageFrom.newBuilder()
        .apply {
            user?.let { setUser(it.toProto()) }
            application?.let { setApplication(it.toProto()) }
        }
        .build()

private fun GraphMessagePreview.toProto(): ProtoMessagePreview =
    ProtoMessagePreview.newBuilder()
        .setId(id.orEmpty())
        .setCreatedDateTime(createdDateTime.orEmpty())
        .apply {
            body?.let { setBody(it.toProto()) }
            from?.let { setSender(it.toProto()) }
        }
        .build()

private fun GraphChat.toProto(): ProtoChatSummary =
    ProtoChatSummary.newBuilder()
        .setId(id)
        .setTopic(topic.orEmpty())
        .setChatType(chatType.orEmpty())
        .setCreatedDateTime(createdDateTime.orEmpty())
        .setLastUpdatedDateTime(lastUpdatedDateTime.orEmpty())
        .apply { lastMessagePreview?.let { setLastMessagePreview(it.toProto()) } }
        .build()

private fun GraphMessage.toProto(): ProtoChatMessage =
    ProtoChatMessage.newBuilder()
        .setId(id)
        .setCreatedDateTime(createdDateTime.orEmpty())
        .setLastModifiedDateTime(lastModifiedDateTime.orEmpty())
        .setMessageType(messageType.orEmpty())
        .apply {
            body?.let { setBody(it.toProto()) }
            from?.let { setSender(it.toProto()) }
        }
        .build()

// === V5b - Teams teams/channels: Graph DTO -> proto mappers ==================

private fun GraphTeam.toProto(): ProtoTeam =
    ProtoTeam.newBuilder()
        .setId(id)
        .setDisplayName(displayName.orEmpty())
        .setDescription(description.orEmpty())
        .build()

private fun GraphChannel.toProto(): ProtoChannel =
    ProtoChannel.newBuilder()
        .setId(id)
        .setDisplayName(displayName.orEmpty())
        .setDescription(description.orEmpty())
        .setMembershipType(membershipType.orEmpty())
        .build()

// === V5c - Mail: Graph DTO <-> proto mappers =================================

private fun GraphEmailAddressDetail.toProto(): ProtoEmailAddress =
    ProtoEmailAddress.newBuilder()
        .setName(name.orEmpty())
        .setAddress(address.orEmpty())
        .build()

private fun GraphMailBody.toProto(): ProtoMailBody =
    ProtoMailBody.newBuilder()
        .setContentType(contentType.orEmpty())
        .setContent(content.orEmpty())
        .build()

private fun GraphEmailAddress.toSenderProto(): ProtoMailSender =
    ProtoMailSender.newBuilder()
        .apply { emailAddress?.let { setEmailAddress(it.toProto()) } }
        .build()

private fun GraphRecipient.toProto(): ProtoRecipient =
    ProtoRecipient.newBuilder()
        .apply { emailAddress?.let { setEmailAddress(it.toProto()) } }
        .build()

private fun GraphMailMessage.toProto(): ProtoMailMessage =
    ProtoMailMessage.newBuilder()
        .setId(id)
        .setSubject(subject.orEmpty())
        .setBodyPreview(bodyPreview.orEmpty())
        .setReceivedDateTime(receivedDateTime.orEmpty())
        .setSentDateTime(sentDateTime.orEmpty())
        .setIsRead(isRead ?: false)
        .setIsDraft(isDraft ?: false)
        .setHasAttachments(hasAttachments ?: false)
        .setImportance(importance.orEmpty())
        .setConversationId(conversationId.orEmpty())
        .apply {
            body?.let { setBody(it.toProto()) }
            from?.let { setSender(it.toSenderProto()) }
            toRecipients?.forEach { addToRecipients(it.toProto()) }
            ccRecipients?.forEach { addCcRecipients(it.toProto()) }
        }
        .build()

private fun ProtoRecipient.toGraph(): GraphRecipient =
    GraphRecipient(
        emailAddress = if (hasEmailAddress()) {
            GraphEmailAddressDetail(
                name = emailAddress.name.takeIf { it.isNotBlank() },
                address = emailAddress.address.takeIf { it.isNotBlank() },
            )
        } else null,
    )

// === V5d - Calendar: Graph DTO <-> proto mappers =============================

private fun GraphDateTimeTimeZone.toProto(): ProtoDateTimeTimeZone =
    ProtoDateTimeTimeZone.newBuilder()
        .setDateTime(dateTime.orEmpty())
        .setTimeZone(timeZone.orEmpty())
        .build()

private fun ProtoDateTimeTimeZone.toGraph(): GraphDateTimeTimeZone =
    GraphDateTimeTimeZone(
        dateTime = dateTime.takeIf { it.isNotBlank() },
        timeZone = timeZone.takeIf { it.isNotBlank() },
    )

private fun GraphLocation.toProto(): ProtoLocation =
    ProtoLocation.newBuilder()
        .setDisplayName(displayName.orEmpty())
        .build()

private fun GraphAttendee.toProto(): ProtoAttendee =
    ProtoAttendee.newBuilder()
        .setType(type.orEmpty())
        .setResponse(status?.response.orEmpty())
        .apply { emailAddress?.let { setEmailAddress(it.toProto()) } }
        .build()

private fun ProtoAttendee.toGraph(): GraphAttendee =
    GraphAttendee(
        emailAddress = if (hasEmailAddress()) {
            GraphEmailAddressDetail(
                name = emailAddress.name.takeIf { it.isNotBlank() },
                address = emailAddress.address.takeIf { it.isNotBlank() },
            )
        } else null,
        type = type.takeIf { it.isNotBlank() } ?: "required",
        status = null,
    )

private fun GraphEvent.toProto(): ProtoCalendarEvent =
    ProtoCalendarEvent.newBuilder()
        .setId(id.orEmpty())
        .setSubject(subject.orEmpty())
        .setIsAllDay(isAllDay ?: false)
        .setIsCancelled(isCancelled ?: false)
        .setIsOnlineMeeting(isOnlineMeeting ?: false)
        .setOnlineMeetingUrl(onlineMeetingUrl.orEmpty())
        .setShowAs(showAs.orEmpty())
        .setWebLink(webLink.orEmpty())
        .setOdataEtag(odataEtag.orEmpty())
        .apply {
            body?.let { setBody(it.toProto()) }
            start?.let { setStart(it.toProto()) }
            end?.let { setEnd(it.toProto()) }
            location?.let { setLocation(it.toProto()) }
            organizer?.emailAddress?.let { setOrganizer(it.toProto()) }
            attendees?.forEach { addAttendees(it.toProto()) }
        }
        .build()
