package com.jervis.client

import com.jervis.dto.ChatRequestContext
import com.jervis.dto.ChatResponse
import com.jervis.service.IAgentOrchestratorService
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType

class AgentOrchestratorRestClient(
    private val httpClient: HttpClient,
    private val baseUrl: String,
) : IAgentOrchestratorService {
    private val apiPath = "$baseUrl/api/agent"

    override suspend fun handle(
        text: String,
        ctx: ChatRequestContext,
    ): ChatResponse =
        httpClient
            .post("$apiPath/handle") {
                contentType(ContentType.Application.Json)
                setBody(ChatRequest(text, ctx))
            }.body()

    private data class ChatRequest(
        val text: String,
        val context: ChatRequestContext,
    )
}
