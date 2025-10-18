package com.jervis.service

import com.jervis.dto.ClientDescriptionResult

interface IClientIndexingService {
    suspend fun updateClientDescriptions(clientId: String): ClientDescriptionResult
}
