package com.jervis.o365gateway.service

import com.jervis.o365gateway.model.CreateEventRequest
import com.jervis.o365gateway.model.GraphCallRecording
import com.jervis.o365gateway.model.GraphCallTranscript
import com.jervis.o365gateway.model.GraphChannel
import com.jervis.o365gateway.model.GraphChat
import com.jervis.o365gateway.model.GraphDriveItem
import com.jervis.o365gateway.model.GraphEvent
import com.jervis.o365gateway.model.GraphListResponse
import com.jervis.o365gateway.model.GraphMailMessage
import com.jervis.o365gateway.model.GraphMessage
import com.jervis.o365gateway.model.GraphMessageBody
import com.jervis.o365gateway.model.GraphOnlineMeeting
import com.jervis.o365gateway.model.GraphSearchResult
import com.jervis.o365gateway.model.GraphTeam
import com.jervis.o365gateway.model.SendMailRequest
import com.jervis.o365gateway.model.SendMessageRequest
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Graph API calls routed through browser pool proxy.
 *
 * All requests go through the Playwright browser in the browser pool pod,
 * which is required for environments with Conditional Access policies.
 * The browser pool executes fetch() from within the browser context,
 * so requests originate from the same IP/device as the login session.
 *
 * URL pattern: {browserPoolUrl}/graph/{clientId}/{graphPath}
 */
class GraphApiService(
    private val httpClient: HttpClient,
    private val tokenService: TokenService,
    private val rateLimiter: GraphRateLimiter,
    private val graphBaseUrl: String = "https://graph.microsoft.com/v1.0",
    private val browserPoolUrl: String = "http://jervis-o365-browser-pool:8090",
) {
    /**
     * Build the URL for a Graph API call — routed through browser pool proxy.
     * The browser pool handles auth internally (captured Bearer token + browser fetch).
     */
    private fun proxyUrl(clientId: String, path: String): String {
        return "$browserPoolUrl/graph/$clientId/$path"
    }

    // -- Teams Chats ----------------------------------------------------------

    suspend fun listChats(clientId: String, top: Int = 20): List<GraphChat> {
        rateLimiter.acquire(clientId)
        val response = httpClient.get(
            proxyUrl(clientId, "me/chats?\$top=$top&\$orderby=lastMessagePreview/createdDateTime desc")
        )
        if (!response.status.isSuccess()) {
            handleGraphError(clientId, response.status.value)
        }
        return response.body<GraphListResponse<GraphChat>>().value
    }

    suspend fun readChat(clientId: String, chatId: String, top: Int = 20): List<GraphMessage> {
        rateLimiter.acquire(clientId)
        val response = httpClient.get(
            proxyUrl(clientId, "me/chats/$chatId/messages?\$top=$top&\$orderby=createdDateTime desc")
        )
        if (!response.status.isSuccess()) {
            handleGraphError(clientId, response.status.value)
        }
        return response.body<GraphListResponse<GraphMessage>>().value
    }

    suspend fun sendChatMessage(
        clientId: String,
        chatId: String,
        content: String,
        contentType: String = "text",
    ): GraphMessage {
        rateLimiter.acquire(clientId)
        val response = httpClient.post(proxyUrl(clientId, "me/chats/$chatId/messages")) {
            contentType(ContentType.Application.Json)
            setBody(SendMessageRequest(body = GraphMessageBody(contentType = contentType, content = content)))
        }
        if (!response.status.isSuccess()) {
            handleGraphError(clientId, response.status.value)
        }
        return response.body<GraphMessage>()
    }

    // -- Teams / Channels -----------------------------------------------------

    suspend fun listTeams(clientId: String): List<GraphTeam> {
        rateLimiter.acquire(clientId)
        val response = httpClient.get(proxyUrl(clientId, "me/joinedTeams"))
        if (!response.status.isSuccess()) {
            handleGraphError(clientId, response.status.value)
        }
        return response.body<GraphListResponse<GraphTeam>>().value
    }

    suspend fun listChannels(clientId: String, teamId: String): List<GraphChannel> {
        rateLimiter.acquire(clientId)
        val response = httpClient.get(proxyUrl(clientId, "teams/$teamId/channels"))
        if (!response.status.isSuccess()) {
            handleGraphError(clientId, response.status.value)
        }
        return response.body<GraphListResponse<GraphChannel>>().value
    }

    suspend fun readChannel(
        clientId: String,
        teamId: String,
        channelId: String,
        top: Int = 20,
    ): List<GraphMessage> {
        rateLimiter.acquire(clientId)
        val response = httpClient.get(
            proxyUrl(clientId, "teams/$teamId/channels/$channelId/messages?\$top=$top")
        )
        if (!response.status.isSuccess()) {
            handleGraphError(clientId, response.status.value)
        }
        return response.body<GraphListResponse<GraphMessage>>().value
    }

    suspend fun sendChannelMessage(
        clientId: String,
        teamId: String,
        channelId: String,
        content: String,
    ): GraphMessage {
        rateLimiter.acquire(clientId)
        val response = httpClient.post(
            proxyUrl(clientId, "teams/$teamId/channels/$channelId/messages")
        ) {
            contentType(ContentType.Application.Json)
            setBody(SendMessageRequest(body = GraphMessageBody(contentType = "text", content = content)))
        }
        if (!response.status.isSuccess()) {
            handleGraphError(clientId, response.status.value)
        }
        return response.body<GraphMessage>()
    }

    // -- Mail (Outlook) -------------------------------------------------------

    suspend fun listMail(
        clientId: String,
        top: Int = 20,
        folder: String = "inbox",
        filter: String? = null,
    ): List<GraphMailMessage> {
        rateLimiter.acquire(clientId)
        val filterParam = if (filter != null) "&\$filter=$filter" else ""
        val response = httpClient.get(
            proxyUrl(clientId, "me/mailFolders/$folder/messages?\$top=$top&\$orderby=receivedDateTime desc$filterParam")
        )
        if (!response.status.isSuccess()) {
            handleGraphError(clientId, response.status.value)
        }
        return response.body<GraphListResponse<GraphMailMessage>>().value
    }

    suspend fun readMail(clientId: String, messageId: String): GraphMailMessage {
        rateLimiter.acquire(clientId)
        val response = httpClient.get(proxyUrl(clientId, "me/messages/$messageId"))
        if (!response.status.isSuccess()) {
            handleGraphError(clientId, response.status.value)
        }
        return response.body<GraphMailMessage>()
    }

    suspend fun sendMail(clientId: String, request: SendMailRequest) {
        rateLimiter.acquire(clientId)
        val response = httpClient.post(proxyUrl(clientId, "me/sendMail")) {
            contentType(ContentType.Application.Json)
            setBody(request)
        }
        if (!response.status.isSuccess()) {
            handleGraphError(clientId, response.status.value)
        }
    }

    // -- Calendar -------------------------------------------------------------

    suspend fun listEvents(
        clientId: String,
        top: Int = 20,
        startDateTime: String? = null,
        endDateTime: String? = null,
    ): List<GraphEvent> {
        rateLimiter.acquire(clientId)
        val path = if (startDateTime != null && endDateTime != null) {
            "me/calendarView?startDateTime=$startDateTime&endDateTime=$endDateTime&\$top=$top&\$orderby=start/dateTime"
        } else {
            "me/events?\$top=$top&\$orderby=start/dateTime"
        }
        val response = httpClient.get(proxyUrl(clientId, path))
        if (!response.status.isSuccess()) {
            handleGraphError(clientId, response.status.value)
        }
        return response.body<GraphListResponse<GraphEvent>>().value
    }

    suspend fun createEvent(clientId: String, request: CreateEventRequest): GraphEvent {
        rateLimiter.acquire(clientId)
        val response = httpClient.post(proxyUrl(clientId, "me/events")) {
            contentType(ContentType.Application.Json)
            setBody(request)
        }
        if (!response.status.isSuccess()) {
            handleGraphError(clientId, response.status.value)
        }
        return response.body<GraphEvent>()
    }

    // -- Online Meetings ------------------------------------------------------

    /**
     * Look up an online meeting by its `joinWebUrl`.
     *
     * Used after polling calendar events to resolve `chatInfo.threadId` (the
     * Teams chat thread that backs the meeting). Returns null if no meeting
     * matches the URL or if the response is empty (e.g. external meetings the
     * current user does not own).
     */
    suspend fun getOnlineMeetingByJoinUrl(clientId: String, joinWebUrl: String): GraphOnlineMeeting? {
        rateLimiter.acquire(clientId)
        val encoded = URLEncoder.encode("JoinWebUrl eq '$joinWebUrl'", StandardCharsets.UTF_8)
        val response = httpClient.get(proxyUrl(clientId, "me/onlineMeetings?\$filter=$encoded"))
        if (!response.status.isSuccess()) {
            handleGraphError(clientId, response.status.value)
        }
        return response.body<GraphListResponse<GraphOnlineMeeting>>().value.firstOrNull()
    }

    /** GET /me/onlineMeetings/{id} — full meeting resource by ID. */
    suspend fun getOnlineMeeting(clientId: String, meetingId: String): GraphOnlineMeeting {
        rateLimiter.acquire(clientId)
        val response = httpClient.get(proxyUrl(clientId, "me/onlineMeetings/$meetingId"))
        if (!response.status.isSuccess()) {
            handleGraphError(clientId, response.status.value)
        }
        return response.body<GraphOnlineMeeting>()
    }

    /**
     * List native Teams call recordings for a meeting.
     * Requires `OnlineMeetingRecording.Read.All` (admin consent in tenant).
     * Falls back to empty list on 403 — caller must use its own audio capture.
     */
    suspend fun listMeetingRecordings(clientId: String, meetingId: String): List<GraphCallRecording> {
        rateLimiter.acquire(clientId)
        val response = httpClient.get(proxyUrl(clientId, "me/onlineMeetings/$meetingId/recordings"))
        if (response.status.value == 403) {
            logger.warn { "Recordings scope not granted for client '$clientId' (admin consent missing)" }
            return emptyList()
        }
        if (!response.status.isSuccess()) {
            handleGraphError(clientId, response.status.value)
        }
        return response.body<GraphListResponse<GraphCallRecording>>().value
    }

    /**
     * List native Teams call transcripts for a meeting.
     * Requires `OnlineMeetingTranscript.Read.All` (admin consent in tenant).
     * Same fallback as recordings on 403.
     */
    suspend fun listMeetingTranscripts(clientId: String, meetingId: String): List<GraphCallTranscript> {
        rateLimiter.acquire(clientId)
        val response = httpClient.get(proxyUrl(clientId, "me/onlineMeetings/$meetingId/transcripts"))
        if (response.status.value == 403) {
            logger.warn { "Transcripts scope not granted for client '$clientId' (admin consent missing)" }
            return emptyList()
        }
        if (!response.status.isSuccess()) {
            handleGraphError(clientId, response.status.value)
        }
        return response.body<GraphListResponse<GraphCallTranscript>>().value
    }

    /** Download a transcript as Microsoft VTT subtitles. */
    suspend fun downloadTranscriptVtt(clientId: String, meetingId: String, transcriptId: String): ByteArray {
        rateLimiter.acquire(clientId)
        val response = httpClient.get(
            proxyUrl(clientId, "me/onlineMeetings/$meetingId/transcripts/$transcriptId/content?\$format=text/vtt"),
        )
        if (!response.status.isSuccess()) {
            handleGraphError(clientId, response.status.value)
        }
        return response.body<ByteArray>()
    }

    // -- OneDrive / SharePoint ------------------------------------------------

    suspend fun listDriveItems(
        clientId: String,
        path: String = "root",
        top: Int = 50,
    ): List<GraphDriveItem> {
        rateLimiter.acquire(clientId)
        val graphPath = if (path == "root") {
            "me/drive/root/children?\$top=$top"
        } else {
            "me/drive/root:/$path:/children?\$top=$top"
        }
        val response = httpClient.get(proxyUrl(clientId, graphPath))
        if (!response.status.isSuccess()) {
            handleGraphError(clientId, response.status.value)
        }
        return response.body<GraphListResponse<GraphDriveItem>>().value
    }

    suspend fun getDriveItem(clientId: String, itemId: String): GraphDriveItem {
        rateLimiter.acquire(clientId)
        val response = httpClient.get(proxyUrl(clientId, "me/drive/items/$itemId"))
        if (!response.status.isSuccess()) {
            handleGraphError(clientId, response.status.value)
        }
        return response.body<GraphDriveItem>()
    }

    suspend fun downloadDriveItem(clientId: String, itemId: String): ByteArray {
        rateLimiter.acquire(clientId)
        val response = httpClient.get(proxyUrl(clientId, "me/drive/items/$itemId/content"))
        if (!response.status.isSuccess()) {
            handleGraphError(clientId, response.status.value)
        }
        return response.body<ByteArray>()
    }

    suspend fun searchDrive(
        clientId: String,
        query: String,
        top: Int = 25,
    ): List<GraphDriveItem> {
        rateLimiter.acquire(clientId)
        val response = httpClient.get(
            proxyUrl(clientId, "me/drive/root/search(q='$query')?\$top=$top")
        )
        if (!response.status.isSuccess()) {
            handleGraphError(clientId, response.status.value)
        }
        return response.body<GraphListResponse<GraphDriveItem>>().value
    }

    // -- helpers --------------------------------------------------------------

    private fun handleGraphError(clientId: String, statusCode: Int) {
        when (statusCode) {
            401 -> {
                tokenService.invalidateCache(clientId)
                throw IllegalStateException("Graph API returned 401 for client '$clientId'. Token expired – session needs re-login.")
            }
            429 -> throw IllegalStateException("Graph API rate limit exceeded for client '$clientId'. Retry later.")
            else -> throw IllegalStateException("Graph API error $statusCode for client '$clientId'.")
        }
    }
}
