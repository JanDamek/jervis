package com.jervis.rpc

import com.jervis.dto.error.ErrorLogCreateRequestDto
import com.jervis.dto.error.ErrorLogDto
import com.jervis.service.IErrorLogService
import com.jervis.service.error.ErrorLogService
import org.bson.types.ObjectId
import org.springframework.stereotype.Service

@Service
class ErrorLogRpcImpl(
    private val service: ErrorLogService,
) : IErrorLogService {
    override suspend fun add(request: ErrorLogCreateRequestDto): ErrorLogDto {
        val throwable = RuntimeException(request.message)
        val saved =
            service.recordError(
                throwable =
                    throwable.apply {
                        if (!request.stackTrace.isNullOrBlank()) initCause(RuntimeException(request.stackTrace))
                    },
                clientId = request.clientId?.let { ObjectId(it) },
                projectId = request.projectId?.let { ObjectId(it) },
                correlationId = request.correlationId,
            )
        return saved.toDto()
    }

    override suspend fun list(clientId: String, limit: Int): List<ErrorLogDto> =
        service.list(ObjectId(clientId), limit).map { it.toDto() }

    override suspend fun listAll(limit: Int): List<ErrorLogDto> =
        service.listAll(limit).map { it.toDto() }

    override suspend fun get(id: String): ErrorLogDto =
        service.get(ObjectId(id)).toDto()

    override suspend fun delete(id: String) {
        service.delete(ObjectId(id))
    }

    override suspend fun deleteAll(clientId: String) {
        service.deleteAll(ObjectId(clientId))
    }
}

private fun com.jervis.domain.error.ErrorLog.toDto(): ErrorLogDto =
    ErrorLogDto(
        id = this.id.toString(),
        clientId = this.clientId?.toString(),
        projectId = this.projectId?.toString(),
        correlationId = this.correlationId,
        message = this.message,
        stackTrace = this.stackTrace,
        causeType = this.causeType,
        createdAt = this.createdAt.toString(),
    )
