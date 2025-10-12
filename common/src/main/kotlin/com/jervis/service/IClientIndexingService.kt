package com.jervis.service

import com.jervis.dto.ClientDescriptionResult
import org.bson.types.ObjectId

interface IClientIndexingService {
    suspend fun updateClientDescriptions(clientId: ObjectId): ClientDescriptionResult
}
