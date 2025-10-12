package com.jervis.client

import com.jervis.dto.ClientDescriptionResult
import com.jervis.service.IClientIndexingService
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.post
import org.bson.types.ObjectId

class ClientIndexingRestClient(
    private val httpClient: HttpClient,
    private val baseUrl: String,
) : IClientIndexingService {
    private val apiPath = "$baseUrl/api/client-indexing"

    override suspend fun updateClientDescriptions(clientId: ObjectId): ClientDescriptionResult =
        httpClient.post("$apiPath/update-descriptions/${clientId.toHexString()}").body()
}
