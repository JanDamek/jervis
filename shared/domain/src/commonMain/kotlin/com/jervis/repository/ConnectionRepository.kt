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
    private val service: IConnectionService,
) : BaseRepository() {
    suspend fun listConnections(): List<ConnectionResponseDto> =
        safeRpcListCall("listConnections") {
            service.getAllConnections()
        }

    suspend fun createConnection(request: ConnectionCreateRequestDto): ConnectionResponseDto =
        safeRpcCall("createConnection") { service.createConnection(request) }

    suspend fun updateConnection(
        id: String,
        request: ConnectionUpdateRequestDto,
    ): ConnectionResponseDto = safeRpcCall("updateConnection") { service.updateConnection(id, request) }

    suspend fun deleteConnection(id: String) {
        safeRpcCall("deleteConnection") { service.deleteConnection(id) }
    }

    suspend fun testConnection(id: String): ConnectionTestResultDto = safeRpcCall("testConnection") { service.testConnection(id) }
}
