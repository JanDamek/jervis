package com.jervis.common.client

import com.jervis.common.dto.CodingExecuteRequest
import com.jervis.common.dto.CodingExecuteResponse
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.service.annotation.HttpExchange
import org.springframework.web.service.annotation.PostExchange

@HttpExchange("/api/coding")
interface ICodingEngineClient {
    @PostExchange("/execute")
    suspend fun execute(
        @RequestBody request: CodingExecuteRequest,
    ): CodingExecuteResponse
}
