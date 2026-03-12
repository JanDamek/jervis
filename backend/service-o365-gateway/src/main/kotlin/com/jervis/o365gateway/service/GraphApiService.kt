package com.jervis.o365gateway.service

import com.jervis.o365gateway.model.GraphChannel
import com.jervis.o365gateway.model.GraphChat
import com.jervis.o365gateway.model.GraphListResponse
import com.jervis.o365gateway.model.GraphMessage
import com.jervis.o365gateway.model.GraphMessageBody
import com.jervis.o365gateway.model.GraphTeam
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
