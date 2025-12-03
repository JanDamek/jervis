package com.jervis.controller.api

import com.jervis.dto.error.ErrorLogDto
import com.jervis.service.IErrorLogService
import com.jervis.dto.error.ErrorLogCreateRequestDto
import com.jervis.service.error.ErrorLogService
import org.bson.types.ObjectId
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/error-logs")
class ErrorLogRestController(
    private val service: ErrorLogService,
) : IErrorLogService {
    @PostMapping
    override suspend fun add(@RequestBody request: ErrorLogCreateRequestDto): com.jervis.dto.error.ErrorLogDto {
        val throwable = RuntimeException(request.message)
        val saved = service.recordError(
            throwable = throwable.apply {
                if (!request.stackTrace.isNullOrBlank()) initCause(RuntimeException(request.stackTrace))
            },
            clientId = request.clientId?.let { org.bson.types.ObjectId(it) },
            projectId = request.projectId?.let { org.bson.types.ObjectId(it) },
            correlationId = request.correlationId,
        )
        return saved.toDto()
    }
    @GetMapping
    override suspend fun list(
        @RequestParam("clientId") clientId: String,
        @RequestParam("limit", defaultValue = "200") limit: Int
    ): List<ErrorLogDto> =
        service.list(ObjectId(clientId), limit).map { it.toDto() }

    @GetMapping("/all")
    override suspend fun listAll(
        @RequestParam("limit", defaultValue = "200") limit: Int
    ): List<ErrorLogDto> =
        service.listAll(limit).map { it.toDto() }

    @GetMapping("/{id}")
    override suspend fun get(@PathVariable("id") id: String): ErrorLogDto = service.get(ObjectId(id)).toDto()

    @DeleteMapping("/{id}")
    override suspend fun delete(@PathVariable("id") id: String) {
        service.delete(ObjectId(id))
    }

    @DeleteMapping
    override suspend fun deleteAll(@RequestParam("clientId") clientId: String) {
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
