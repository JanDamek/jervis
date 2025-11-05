package com.jervis.service

import com.jervis.dto.error.ErrorLogDto
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.service.annotation.DeleteExchange
import org.springframework.web.service.annotation.GetExchange
import org.springframework.web.service.annotation.HttpExchange

@HttpExchange("/api/error-logs")
interface IErrorLogService {
    @GetExchange
    suspend fun list(
        @RequestParam("clientId") clientId: String,
        @RequestParam("limit") limit: Int = 200,
    ): List<ErrorLogDto>

    @GetExchange("/{id}")
    suspend fun get(@PathVariable("id") id: String): ErrorLogDto

    @DeleteExchange("/{id}")
    suspend fun delete(@PathVariable("id") id: String)

    @DeleteExchange
    suspend fun deleteAll(@RequestParam("clientId") clientId: String)
}
