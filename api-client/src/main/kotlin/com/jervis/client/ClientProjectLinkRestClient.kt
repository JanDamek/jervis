package com.jervis.client

import com.jervis.dto.ClientProjectLinkDto
import com.jervis.service.IClientProjectLinkService
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.put

class ClientProjectLinkRestClient(
    private val httpClient: HttpClient,
    private val baseUrl: String,
) : IClientProjectLinkService {
    private val apiPath = "$baseUrl/api/client-project-links"

    override suspend fun listForClient(clientId: String): List<ClientProjectLinkDto> = httpClient.get("$apiPath/client/$clientId").body()

    override suspend fun get(
        clientId: String,
        projectId: String,
    ): ClientProjectLinkDto? = httpClient.get("$apiPath/client/$clientId/project/$projectId").body()

    override suspend fun upsert(
        clientId: String,
        projectId: String,
        isDisabled: Boolean?,
        anonymizationEnabled: Boolean?,
        historical: Boolean?,
    ): ClientProjectLinkDto =
        httpClient
            .post("$apiPath/client/$clientId/project/$projectId") {
                isDisabled?.let { parameter("isDisabled", it) }
                anonymizationEnabled?.let { parameter("anonymizationEnabled", it) }
                historical?.let { parameter("historical", it) }
            }.body()

    override suspend fun toggleDisabled(
        clientId: String,
        projectId: String,
    ): ClientProjectLinkDto = httpClient.put("$apiPath/client/$clientId/project/$projectId/toggle-disabled").body()

    override suspend fun toggleAnonymization(
        clientId: String,
        projectId: String,
    ): ClientProjectLinkDto = httpClient.put("$apiPath/client/$clientId/project/$projectId/toggle-anonymization").body()

    override suspend fun toggleHistorical(
        clientId: String,
        projectId: String,
    ): ClientProjectLinkDto = httpClient.put("$apiPath/client/$clientId/project/$projectId/toggle-historical").body()

    override suspend fun delete(
        clientId: String,
        projectId: String,
    ) {
        httpClient.delete("$apiPath/client/$clientId/project/$projectId")
    }
}
