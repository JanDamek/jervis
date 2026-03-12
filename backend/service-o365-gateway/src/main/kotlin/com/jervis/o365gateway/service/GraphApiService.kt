package com.jervis.o365gateway.service

import com.jervis.o365gateway.model.CreateEventRequest
import com.jervis.o365gateway.model.GraphChannel
import com.jervis.o365gateway.model.GraphChat
import com.jervis.o365gateway.model.GraphDriveItem
import com.jervis.o365gateway.model.GraphEvent
import com.jervis.o365gateway.model.GraphListResponse
import com.jervis.o365gateway.model.GraphMailMessage
import com.jervis.o365gateway.model.GraphMessage
import com.jervis.o365gateway.model.GraphMessageBody
import com.jervis.o365gateway.model.GraphSearchResult
import com.jervis.o365gateway.model.GraphTeam
import com.jervis.o365gateway.model.SendMailRequest
import com.jervis.o365gateway.model.SendMessageRequest
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
 * Raw HTTP calls to Microsoft Graph API using relay Bearer tokens.
 *
 * No Graph SDK dependency – uses Ktor HTTP client directly.
 * All calls go through rate limiter and token service.
 */
class GraphApiService(
    private val httpClient: HttpClient,
    private val tokenService: TokenService,
    private val rateLimiter: GraphRateLimiter,
    private val graphBaseUrl: String = "https://graph.microsoft.com/v1.0",
) {
    // -- Teams Chats ----------------------------------------------------------

    suspend fun listChats(clientId: String, top: Int = 20): List<GraphChat> {
        rateLimiter.acquire(clientId)
        val token = requireToken(clientId)
        val response = httpClient.get(
            "$graphBaseUrl/me/chats?\$top=$top&\$orderby=lastMessagePreview/createdDateTime desc"
        ) {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        if (!response.status.isSuccess()) {
            handleGraphError(clientId, response.status.value)
        }
        return response.body<GraphListResponse<GraphChat>>().value
    }

    suspend fun readChat(clientId: String, chatId: String, top: Int = 20): List<GraphMessage> {
        rateLimiter.acquire(clientId)
        val token = requireToken(clientId)
        val response = httpClient.get(
            "$graphBaseUrl/me/chats/$chatId/messages?\$top=$top&\$orderby=createdDateTime desc"
        ) {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
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
        val token = requireToken(clientId)
        val response = httpClient.post("$graphBaseUrl/me/chats/$chatId/messages") {
            header(HttpHeaders.Authorization, "Bearer $token")
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
        val token = requireToken(clientId)
        val response = httpClient.get("$graphBaseUrl/me/joinedTeams") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        if (!response.status.isSuccess()) {
            handleGraphError(clientId, response.status.value)
        }
        return response.body<GraphListResponse<GraphTeam>>().value
    }

    suspend fun listChannels(clientId: String, teamId: String): List<GraphChannel> {
        rateLimiter.acquire(clientId)
        val token = requireToken(clientId)
        val response = httpClient.get("$graphBaseUrl/teams/$teamId/channels") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
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
        val token = requireToken(clientId)
        val response = httpClient.get(
            "$graphBaseUrl/teams/$teamId/channels/$channelId/messages?\$top=$top"
        ) {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
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
        val token = requireToken(clientId)
        val response = httpClient.post(
            "$graphBaseUrl/teams/$teamId/channels/$channelId/messages"
        ) {
            header(HttpHeaders.Authorization, "Bearer $token")
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
        val token = requireToken(clientId)
        val filterParam = if (filter != null) "&\$filter=$filter" else ""
        val response = httpClient.get(
            "$graphBaseUrl/me/mailFolders/$folder/messages?\$top=$top&\$orderby=receivedDateTime desc$filterParam"
        ) {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        if (!response.status.isSuccess()) {
            handleGraphError(clientId, response.status.value)
        }
        return response.body<GraphListResponse<GraphMailMessage>>().value
    }

    suspend fun readMail(clientId: String, messageId: String): GraphMailMessage {
        rateLimiter.acquire(clientId)
        val token = requireToken(clientId)
        val response = httpClient.get("$graphBaseUrl/me/messages/$messageId") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        if (!response.status.isSuccess()) {
            handleGraphError(clientId, response.status.value)
        }
        return response.body<GraphMailMessage>()
    }

    suspend fun sendMail(clientId: String, request: SendMailRequest) {
        rateLimiter.acquire(clientId)
        val token = requireToken(clientId)
        val response = httpClient.post("$graphBaseUrl/me/sendMail") {
            header(HttpHeaders.Authorization, "Bearer $token")
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
        val token = requireToken(clientId)
        val url = if (startDateTime != null && endDateTime != null) {
            "$graphBaseUrl/me/calendarView?startDateTime=$startDateTime&endDateTime=$endDateTime&\$top=$top&\$orderby=start/dateTime"
        } else {
            "$graphBaseUrl/me/events?\$top=$top&\$orderby=start/dateTime"
        }
        val response = httpClient.get(url) {
            header(HttpHeaders.Authorization, "Bearer $token")
            header("Prefer", "outlook.timezone=\"UTC\"")
        }
        if (!response.status.isSuccess()) {
            handleGraphError(clientId, response.status.value)
        }
        return response.body<GraphListResponse<GraphEvent>>().value
    }

    suspend fun createEvent(clientId: String, request: CreateEventRequest): GraphEvent {
        rateLimiter.acquire(clientId)
        val token = requireToken(clientId)
        val response = httpClient.post("$graphBaseUrl/me/events") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(request)
        }
        if (!response.status.isSuccess()) {
            handleGraphError(clientId, response.status.value)
        }
        return response.body<GraphEvent>()
    }

    // -- OneDrive / SharePoint ------------------------------------------------

    suspend fun listDriveItems(
        clientId: String,
        path: String = "root",
        top: Int = 50,
    ): List<GraphDriveItem> {
        rateLimiter.acquire(clientId)
        val token = requireToken(clientId)
        val url = if (path == "root") {
            "$graphBaseUrl/me/drive/root/children?\$top=$top"
        } else {
            "$graphBaseUrl/me/drive/root:/$path:/children?\$top=$top"
        }
        val response = httpClient.get(url) {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        if (!response.status.isSuccess()) {
            handleGraphError(clientId, response.status.value)
        }
        return response.body<GraphListResponse<GraphDriveItem>>().value
    }

    suspend fun getDriveItem(clientId: String, itemId: String): GraphDriveItem {
        rateLimiter.acquire(clientId)
        val token = requireToken(clientId)
        val response = httpClient.get("$graphBaseUrl/me/drive/items/$itemId") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        if (!response.status.isSuccess()) {
            handleGraphError(clientId, response.status.value)
        }
        return response.body<GraphDriveItem>()
    }

    suspend fun downloadDriveItem(clientId: String, itemId: String): ByteArray {
        rateLimiter.acquire(clientId)
        val token = requireToken(clientId)
        val response = httpClient.get("$graphBaseUrl/me/drive/items/$itemId/content") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
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
        val token = requireToken(clientId)
        val response = httpClient.get(
            "$graphBaseUrl/me/drive/root/search(q='$query')?\$top=$top"
        ) {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        if (!response.status.isSuccess()) {
            handleGraphError(clientId, response.status.value)
        }
        return response.body<GraphListResponse<GraphDriveItem>>().value
    }

    // -- helpers --------------------------------------------------------------

    private suspend fun requireToken(clientId: String): String {
        return tokenService.getToken(clientId)
            ?: throw IllegalStateException("No valid token for client '$clientId'. Session may need login.")
    }

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
