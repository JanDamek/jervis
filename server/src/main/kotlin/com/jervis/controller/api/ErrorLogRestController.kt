package com.jervis.controller.api

import com.jervis.dto.error.ErrorLogDto
import com.jervis.service.IErrorLogService
import com.jervis.service.error.ErrorLogService
import org.bson.types.ObjectId
import org.springframework.web.bind.annotation.RestController

@RestController
class ErrorLogRestController(
    private val service: ErrorLogService,
) : IErrorLogService {
    override suspend fun list(clientId: String, limit: Int): List<ErrorLogDto> =
        service.list(ObjectId(clientId), limit).map { it.toDto() }

    override suspend fun get(id: String): ErrorLogDto = service.get(ObjectId(id)).toDto()

    override suspend fun delete(id: String) {
        service.delete(ObjectId(id))
    }

    override suspend fun deleteAll(clientId: String) {
        service.deleteAll(ObjectId(clientId))
    }
}

private fun com.jervis.domain.error.ErrorLog.toDto(): ErrorLogDto =
    ErrorLogDto(
        id = this.id.toHexString(),
        clientId = this.clientId?.toHexString(),
        projectId = this.projectId?.toHexString(),
        correlationId = this.correlationId,
        message = this.message,
        stackTrace = this.stackTrace,
        causeType = this.causeType,
        createdAt = this.createdAt.toString(),
    )
