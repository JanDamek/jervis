package com.jervis.repository

import com.jervis.dto.connection.ConnectionCreateRequestDto
import com.jervis.dto.connection.ConnectionResponseDto
import com.jervis.dto.connection.ConnectionTestResultDto
import com.jervis.dto.connection.ConnectionUpdateRequestDto
import com.jervis.service.IConnectionService

/**
 * Connection Repository
 * Provides business logic layer over IConnectionService
 */
class ConnectionRepository(
    private val service: IConnectionService
) {
    suspend fun listConnections(): List<ConnectionResponseDto> {
        return service.getAllConnections()
    }

    suspend fun getConnection(id: String): ConnectionResponseDto? {
        return service.getConnectionById(id)
    }

    suspend fun createConnection(request: ConnectionCreateRequestDto): ConnectionResponseDto {
        return service.createConnection(request)
    }

    suspend fun updateConnection(id: String, request: ConnectionUpdateRequestDto): ConnectionResponseDto {
        return service.updateConnection(id, request)
    }

    suspend fun deleteConnection(id: String) {
        service.deleteConnection(id)
    }

    suspend fun testConnection(id: String): ConnectionTestResultDto {
        return service.testConnection(id)
    }
}
