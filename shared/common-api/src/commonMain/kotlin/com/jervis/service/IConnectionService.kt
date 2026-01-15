package com.jervis.service

import com.jervis.dto.connection.ConnectionCreateRequestDto
import com.jervis.dto.connection.ConnectionResponseDto
import com.jervis.dto.connection.ConnectionTestResultDto
import com.jervis.dto.connection.ConnectionUpdateRequestDto
import kotlinx.rpc.annotations.Rpc

@Rpc
interface IConnectionService {
    suspend fun getAllConnections(): List<ConnectionResponseDto>

    suspend fun getConnectionById(id: String): ConnectionResponseDto?

    suspend fun createConnection(request: ConnectionCreateRequestDto): ConnectionResponseDto

    suspend fun updateConnection(
        id: String,
        request: ConnectionUpdateRequestDto,
    ): ConnectionResponseDto

    suspend fun deleteConnection(id: String)

    suspend fun testConnection(id: String): ConnectionTestResultDto
}
