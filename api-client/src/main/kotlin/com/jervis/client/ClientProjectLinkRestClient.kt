package com.jervis.client

import com.jervis.entity.mongo.ClientProjectLinkDocument
import com.jervis.service.IClientProjectLinkService
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.put
import org.bson.types.ObjectId

class ClientProjectLinkRestClient(
    private val httpClient: HttpClient,
    private val baseUrl: String,
) : IClientProjectLinkService {
    private val apiPath = "$baseUrl/api/client-project-links"

    override suspend fun listForClient(clientId: ObjectId): List<ClientProjectLinkDocument> =
        httpClient.get("$apiPath/client/$clientId").body()

    override suspend fun get(
        clientId: ObjectId,
        projectId: ObjectId,
    ): ClientProjectLinkDocument? = httpClient.get("$apiPath/client/$clientId/project/$projectId").body()

    override suspend fun upsert(
        clientId: ObjectId,
        projectId: ObjectId,
        isDisabled: Boolean?,
        anonymizationEnabled: Boolean?,
        historical: Boolean?,
    ): ClientProjectLinkDocument =
        httpClient
            .post("$apiPath/client/$clientId/project/$projectId") {
                isDisabled?.let { parameter("isDisabled", it) }
                anonymizationEnabled?.let { parameter("anonymizationEnabled", it) }
                historical?.let { parameter("historical", it) }
            }.body()

    override suspend fun toggleDisabled(
        clientId: ObjectId,
        projectId: ObjectId,
    ): ClientProjectLinkDocument = httpClient.put("$apiPath/client/$clientId/project/$projectId/toggle-disabled").body()

    override suspend fun toggleAnonymization(
        clientId: ObjectId,
        projectId: ObjectId,
    ): ClientProjectLinkDocument = httpClient.put("$apiPath/client/$clientId/project/$projectId/toggle-anonymization").body()

    override suspend fun toggleHistorical(
        clientId: ObjectId,
        projectId: ObjectId,
    ): ClientProjectLinkDocument = httpClient.put("$apiPath/client/$clientId/project/$projectId/toggle-historical").body()

    override suspend fun delete(
        clientId: ObjectId,
        projectId: ObjectId,
    ) {
        httpClient.delete("$apiPath/client/$clientId/project/$projectId")
    }
}
