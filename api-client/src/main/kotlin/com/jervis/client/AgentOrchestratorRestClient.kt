package com.jervis.client

import com.jervis.dto.ChatRequest
import com.jervis.service.IAgentOrchestratorService
import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.runBlocking

class AgentOrchestratorRestClient(
    private val httpClient: HttpClient,
    private val baseUrl: String,
) : IAgentOrchestratorService {
    private val apiPath = "$baseUrl/api/agent"

    override fun handle(request: ChatRequest) {
        runBlocking {
            // Fire-and-forget: send request and receive 202 Accepted
            // Actual response will come via WebSocket
            httpClient.post("$apiPath/handle") {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        }
    }
}
