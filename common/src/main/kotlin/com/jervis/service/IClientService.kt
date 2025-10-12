package com.jervis.service

import com.jervis.entity.mongo.ClientDocument
import org.bson.types.ObjectId

interface IClientService {
    suspend fun create(client: ClientDocument): ClientDocument

    suspend fun update(
        id: ObjectId,
        client: ClientDocument,
    ): ClientDocument

    suspend fun delete(id: ObjectId)

    suspend fun list(): List<ClientDocument>

    suspend fun getClientById(id: ObjectId): ClientDocument?
}
