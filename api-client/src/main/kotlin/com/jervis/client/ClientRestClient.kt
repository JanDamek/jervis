package com.jervis.client

import com.jervis.entity.mongo.ClientDocument
import com.jervis.service.IClientService
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import org.bson.types.ObjectId

class ClientRestClient(
    private val httpClient: HttpClient,
    private val baseUrl: String,
) : IClientService {
    private val apiPath = "$baseUrl/api/clients"

    override suspend fun create(client: ClientDocument): ClientDocument =
        httpClient
            .post(apiPath) {
                contentType(ContentType.Application.Json)
                setBody(client)
            }.body()

    override suspend fun update(
        id: ObjectId,
        client: ClientDocument,
    ): ClientDocument =
        httpClient
            .put("$apiPath/$id") {
                contentType(ContentType.Application.Json)
                setBody(client)
            }.body()

    override suspend fun delete(id: ObjectId) {
        httpClient.delete("$apiPath/$id")
    }

    override suspend fun list(): List<ClientDocument> = httpClient.get(apiPath).body()

    override suspend fun getClientById(id: ObjectId): ClientDocument? = httpClient.get("$apiPath/$id").body()
}
