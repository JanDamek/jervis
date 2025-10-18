package com.jervis.client

import com.jervis.dto.ClientDto
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

class ClientRestClient(
    private val httpClient: HttpClient,
    private val baseUrl: String,
) : IClientService {
    private val apiPath = "$baseUrl/api/clients"

    override suspend fun create(clientName: String): ClientDto =
        httpClient
            .post(apiPath) {
                contentType(ContentType.Application.Json)
                setBody(clientName)
            }.body()

    override suspend fun create(client: ClientDto): ClientDto =
        httpClient
            .post(apiPath) {
                contentType(ContentType.Application.Json)
                setBody(client)
            }.body()

    override suspend fun update(client: ClientDto): ClientDto =
        httpClient
            .put(apiPath) {
                contentType(ContentType.Application.Json)
                setBody(client)
            }.body()

    override suspend fun delete(id: String) {
        httpClient.delete("$apiPath/$id")
    }

    override suspend fun list(): List<ClientDto> = httpClient.get(apiPath).body()

    override suspend fun getClientById(id: String): ClientDto? = httpClient.get("$apiPath/$id").body()
}
