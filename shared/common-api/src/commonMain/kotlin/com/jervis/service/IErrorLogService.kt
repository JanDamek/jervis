package com.jervis.service

import com.jervis.dto.error.ErrorLogCreateRequestDto
import com.jervis.dto.error.ErrorLogDto
import kotlinx.rpc.annotations.Rpc

@Rpc
interface IErrorLogService {
    suspend fun add(request: ErrorLogCreateRequestDto): ErrorLogDto

    suspend fun list(
        clientId: String,
        limit: Int = 200,
    ): List<ErrorLogDto>

    suspend fun listAll(limit: Int = 200): List<ErrorLogDto>

    suspend fun get(id: String): ErrorLogDto

    suspend fun delete(id: String)

    suspend fun deleteAll(clientId: String)
}
