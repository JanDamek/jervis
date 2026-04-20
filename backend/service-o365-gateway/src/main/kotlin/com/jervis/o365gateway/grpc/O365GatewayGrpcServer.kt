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
import com.jervis.contracts.o365_gateway.CallRecording as ProtoCallRecording
import com.jervis.contracts.o365_gateway.CallTranscript as ProtoCallTranscript
import com.jervis.contracts.o365_gateway.ChatInfo as ProtoChatInfo
import com.jervis.contracts.o365_gateway.DriveItem as ProtoDriveItem
import com.jervis.contracts.o365_gateway.DriveItemRequest
import com.jervis.contracts.o365_gateway.FileInfo as ProtoFileInfo
import com.jervis.contracts.o365_gateway.FolderInfo as ProtoFolderInfo
import com.jervis.contracts.o365_gateway.Hashes as ProtoHashes
import com.jervis.contracts.o365_gateway.ItemReference as ProtoItemReference
import com.jervis.contracts.o365_gateway.ListDriveItemsRequest
import com.jervis.contracts.o365_gateway.ListDriveItemsResponse
import com.jervis.contracts.o365_gateway.ListCalendarEventsRequest
import com.jervis.contracts.o365_gateway.ListCalendarEventsResponse
import com.jervis.contracts.o365_gateway.ListRecordingsResponse
import com.jervis.contracts.o365_gateway.ListTranscriptsResponse
import com.jervis.contracts.o365_gateway.Location as ProtoLocation
import com.jervis.contracts.o365_gateway.MeetingParticipant as ProtoMeetingParticipant
import com.jervis.contracts.o365_gateway.MeetingParticipants as ProtoMeetingParticipants
import com.jervis.contracts.o365_gateway.OnlineMeeting as ProtoOnlineMeeting
import com.jervis.contracts.o365_gateway.OnlineMeetingByJoinUrlRequest
import com.jervis.contracts.o365_gateway.OnlineMeetingRequest
import com.jervis.contracts.o365_gateway.SearchDriveRequest
import com.jervis.contracts.o365_gateway.SessionStatus as ProtoSessionStatus
import com.jervis.contracts.o365_gateway.SessionStatusRequest
import com.jervis.contracts.o365_gateway.TranscriptContent
import com.jervis.contracts.o365_gateway.TranscriptRef
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
import com.jervis.contracts.o365_gateway.O365GatewayServiceGrpcKt
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
import com.jervis.o365gateway.model.GraphCallRecording
import com.jervis.o365gateway.model.GraphCallTranscript
import com.jervis.o365gateway.model.GraphChatInfo
import com.jervis.o365gateway.model.GraphDateTimeTimeZone
import com.jervis.o365gateway.model.GraphDriveItem
import com.jervis.o365gateway.model.GraphEmailAddress
import com.jervis.o365gateway.model.GraphEmailAddressDetail
import com.jervis.o365gateway.model.GraphEvent
import com.jervis.o365gateway.model.GraphFileInfo
import com.jervis.o365gateway.model.GraphFolderInfo
import com.jervis.o365gateway.model.GraphHashes
import com.jervis.o365gateway.model.GraphItemReference
import com.jervis.o365gateway.model.GraphLocation
import com.jervis.o365gateway.model.GraphMeetingParticipant
import com.jervis.o365gateway.model.GraphMeetingParticipants
import com.jervis.o365gateway.model.GraphOnlineMeeting
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
import mu.KotlinLogging
import java.util.concurrent.TimeUnit

private val logger = KotlinLogging.logger {}

/**
 * gRPC server for the O365 Gateway.
 *
 * All RPCs are strongly typed (V5a-V5g landed slices). Teams chats,
 * channels, Mail, Calendar, Online meetings, Drive, and Session share
 * one O365GatewayService so every consumer dials a single channel.
 */
class O365GatewayGrpcServer(
    private val graphApi: GraphApiService,
    private val browserPool: BrowserPoolClient,
    private val port: Int = 5501,
) {
    private var server: Server? = null

    fun start() {
        val interceptor = ServerContextInterceptor()
        val servicer = GatewayServicer(graphApi, browserPool)
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
) : O365GatewayServiceGrpcKt.O365GatewayServiceCoroutineImplBase() {

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

    // === V5e - Online meetings typed =========================================

    override suspend fun getOnlineMeetingByJoinUrl(
        request: OnlineMeetingByJoinUrlRequest,
    ): ProtoOnlineMeeting {
        val meeting = graphApi.getOnlineMeetingByJoinUrl(request.clientId, request.joinWebUrl)
            ?: throw io.grpc.StatusException(
                io.grpc.Status.NOT_FOUND.withDescription("No meeting matches joinWebUrl"),
            )
        return meeting.toProto()
    }

    override suspend fun getOnlineMeeting(request: OnlineMeetingRequest): ProtoOnlineMeeting {
        val meeting = graphApi.getOnlineMeeting(request.clientId, request.meetingId)
        return meeting.toProto()
    }

    override suspend fun listMeetingRecordings(
        request: OnlineMeetingRequest,
    ): ListRecordingsResponse {
        val recordings = graphApi.listMeetingRecordings(request.clientId, request.meetingId)
        return ListRecordingsResponse.newBuilder()
            .apply { recordings.forEach { addRecordings(it.toProto()) } }
            .build()
    }

    override suspend fun listMeetingTranscripts(
        request: OnlineMeetingRequest,
    ): ListTranscriptsResponse {
        val transcripts = graphApi.listMeetingTranscripts(request.clientId, request.meetingId)
        return ListTranscriptsResponse.newBuilder()
            .apply { transcripts.forEach { addTranscripts(it.toProto()) } }
            .build()
    }

    override suspend fun downloadTranscriptVtt(request: TranscriptRef): TranscriptContent {
        val vtt = graphApi.downloadTranscriptVtt(
            request.clientId, request.meetingId, request.transcriptId,
        )
        return TranscriptContent.newBuilder()
            .setVtt(ByteString.copyFrom(vtt))
            .setContentType("text/vtt")
            .build()
    }

    // === V5f - Drive (OneDrive / SharePoint) typed ===========================

    override suspend fun listDriveItems(
        request: ListDriveItemsRequest,
    ): ListDriveItemsResponse {
        val top = if (request.top == 0) 50 else request.top
        val path = request.path.ifBlank { "root" }
        val items = graphApi.listDriveItems(request.clientId, path, top)
        return ListDriveItemsResponse.newBuilder()
            .apply { items.forEach { addItems(it.toProto()) } }
            .build()
    }

    override suspend fun getDriveItem(request: DriveItemRequest): ProtoDriveItem {
        val item = graphApi.getDriveItem(request.clientId, request.itemId)
        return item.toProto()
    }

    override suspend fun searchDrive(request: SearchDriveRequest): ListDriveItemsResponse {
        val top = if (request.top == 0) 25 else request.top
        val items = graphApi.searchDrive(request.clientId, request.query, top)
        return ListDriveItemsResponse.newBuilder()
            .apply { items.forEach { addItems(it.toProto()) } }
            .build()
    }

    // === V5g - Session status typed ==========================================

    override suspend fun getSessionStatus(request: SessionStatusRequest): ProtoSessionStatus {
        val status = browserPool.getSessionStatus(request.clientId)
            ?: throw io.grpc.StatusException(
                io.grpc.Status.NOT_FOUND.withDescription(
                    "No session for client '${request.clientId}'",
                ),
            )
        return ProtoSessionStatus.newBuilder()
            .setClientId(status.clientId)
            .setState(status.state)
            .setLastActivity(status.lastActivity.orEmpty())
            .setLastTokenExtract(status.lastTokenExtract.orEmpty())
            .setNovncUrl(status.novncUrl.orEmpty())
            .build()
    }

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
        .also { b ->
            user?.let { b.setUser(it.toProto()) }
            application?.let { b.setApplication(it.toProto()) }
        }
        .build()

private fun GraphMessagePreview.toProto(): ProtoMessagePreview =
    ProtoMessagePreview.newBuilder()
        .setId(id.orEmpty())
        .setCreatedDateTime(createdDateTime.orEmpty())
        .also { b ->
            body?.let { b.setBody(it.toProto()) }
            from?.let { b.setSender(it.toProto()) }
        }
        .build()

private fun GraphChat.toProto(): ProtoChatSummary =
    ProtoChatSummary.newBuilder()
        .setId(id)
        .setTopic(topic.orEmpty())
        .setChatType(chatType.orEmpty())
        .setCreatedDateTime(createdDateTime.orEmpty())
        .setLastUpdatedDateTime(lastUpdatedDateTime.orEmpty())
        .also { b -> lastMessagePreview?.let { b.setLastMessagePreview(it.toProto()) } }
        .build()

private fun GraphMessage.toProto(): ProtoChatMessage =
    ProtoChatMessage.newBuilder()
        .setId(id)
        .setCreatedDateTime(createdDateTime.orEmpty())
        .setLastModifiedDateTime(lastModifiedDateTime.orEmpty())
        .setMessageType(messageType.orEmpty())
        .also { b ->
            body?.let { b.setBody(it.toProto()) }
            from?.let { b.setSender(it.toProto()) }
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
        .also { b -> emailAddress?.let { b.setEmailAddress(it.toProto()) } }
        .build()

private fun GraphRecipient.toProto(): ProtoRecipient =
    ProtoRecipient.newBuilder()
        .also { b -> emailAddress?.let { b.setEmailAddress(it.toProto()) } }
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
        .also { b ->
            body?.let { b.setBody(it.toProto()) }
            from?.let { b.setSender(it.toSenderProto()) }
            toRecipients?.forEach { b.addToRecipients(it.toProto()) }
            ccRecipients?.forEach { b.addCcRecipients(it.toProto()) }
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
        .also { b -> emailAddress?.let { b.setEmailAddress(it.toProto()) } }
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

// === V5e - Online meetings: Graph DTO -> proto mappers =======================

private fun GraphChatInfo.toProto(): ProtoChatInfo =
    ProtoChatInfo.newBuilder()
        .setThreadId(threadId.orEmpty())
        .setMessageId(messageId.orEmpty())
        .setReplyChainMessageId(replyChainMessageId.orEmpty())
        .build()

private fun GraphMeetingParticipant.toProto(): ProtoMeetingParticipant =
    ProtoMeetingParticipant.newBuilder()
        .setRole(role.orEmpty())
        .setUpn(upn.orEmpty())
        .also { b -> identity?.user?.let { b.setUser(it.toProto()) } }
        .build()

private fun GraphMeetingParticipants.toProto(): ProtoMeetingParticipants =
    ProtoMeetingParticipants.newBuilder()
        .also { b ->
            organizer?.let { b.setOrganizer(it.toProto()) }
            attendees?.forEach { b.addAttendees(it.toProto()) }
        }
        .build()

private fun GraphOnlineMeeting.toProto(): ProtoOnlineMeeting =
    ProtoOnlineMeeting.newBuilder()
        .setId(id)
        .setJoinWebUrl(joinWebUrl.orEmpty())
        .setSubject(subject.orEmpty())
        .setStartDateTime(startDateTime.orEmpty())
        .setEndDateTime(endDateTime.orEmpty())
        .also { b ->
            chatInfo?.let { b.setChatInfo(it.toProto()) }
            participants?.let { b.setParticipants(it.toProto()) }
        }
        .build()

private fun GraphCallRecording.toProto(): ProtoCallRecording =
    ProtoCallRecording.newBuilder()
        .setId(id)
        .setMeetingId(meetingId.orEmpty())
        .setCallId(callId.orEmpty())
        .setCreatedDateTime(createdDateTime.orEmpty())
        .setRecordingContentUrl(recordingContentUrl.orEmpty())
        .setContentCorrelationId(contentCorrelationId.orEmpty())
        .build()

private fun GraphCallTranscript.toProto(): ProtoCallTranscript =
    ProtoCallTranscript.newBuilder()
        .setId(id)
        .setMeetingId(meetingId.orEmpty())
        .setCreatedDateTime(createdDateTime.orEmpty())
        .setTranscriptContentUrl(transcriptContentUrl.orEmpty())
        .build()

// === V5f - Drive: Graph DTO -> proto mappers =================================

private fun GraphHashes.toProto(): ProtoHashes =
    ProtoHashes.newBuilder()
        .setSha256Hash(sha256Hash.orEmpty())
        .build()

private fun GraphFileInfo.toProto(): ProtoFileInfo =
    ProtoFileInfo.newBuilder()
        .setMimeType(mimeType.orEmpty())
        .also { b -> hashes?.let { b.setHashes(it.toProto()) } }
        .build()

private fun GraphFolderInfo.toProto(): ProtoFolderInfo =
    ProtoFolderInfo.newBuilder()
        .setChildCount(childCount ?: 0)
        .build()

private fun GraphItemReference.toProto(): ProtoItemReference =
    ProtoItemReference.newBuilder()
        .setDriveId(driveId.orEmpty())
        .setId(id.orEmpty())
        .setPath(path.orEmpty())
        .build()

private fun GraphDriveItem.toProto(): ProtoDriveItem =
    ProtoDriveItem.newBuilder()
        .setId(id)
        .setName(name.orEmpty())
        .setSize(size ?: 0L)
        .setCreatedDateTime(createdDateTime.orEmpty())
        .setLastModifiedDateTime(lastModifiedDateTime.orEmpty())
        .setWebUrl(webUrl.orEmpty())
        .setDownloadUrl(downloadUrl.orEmpty())
        .also { b ->
            file?.let { b.setFile(it.toProto()) }
            folder?.let { b.setFolder(it.toProto()) }
            parentReference?.let { b.setParentReference(it.toProto()) }
        }
        .build()

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
        .also { b ->
            body?.let { b.setBody(it.toProto()) }
            start?.let { b.setStart(it.toProto()) }
            end?.let { b.setEnd(it.toProto()) }
            location?.let { b.setLocation(it.toProto()) }
            organizer?.emailAddress?.let { b.setOrganizer(it.toProto()) }
            attendees?.forEach { b.addAttendees(it.toProto()) }
        }
        .build()
