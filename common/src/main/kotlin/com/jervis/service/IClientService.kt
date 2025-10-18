package com.jervis.service

import com.jervis.dto.ClientDto

interface IClientService {
    suspend fun create(clientName: String): ClientDto

    suspend fun create(client: ClientDto): ClientDto

    suspend fun update(client: ClientDto): ClientDto

    suspend fun delete(id: String)

    suspend fun list(): List<ClientDto>

    suspend fun getClientById(id: String): ClientDto?
}
