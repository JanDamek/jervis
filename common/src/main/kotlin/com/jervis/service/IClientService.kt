package com.jervis.service

import com.jervis.dto.ClientDto
import org.bson.types.ObjectId

interface IClientService {
    suspend fun create(client: ClientDto): ClientDto

    suspend fun update(
        id: ObjectId,
        client: ClientDto,
    ): ClientDto

    suspend fun delete(id: ObjectId)

    suspend fun list(): List<ClientDto>

    suspend fun getClientById(id: ObjectId): ClientDto?
}
