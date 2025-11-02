package com.jervis.common.client

import com.jervis.common.dto.TikaProcessRequest
import com.jervis.common.dto.TikaProcessResult
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.service.annotation.HttpExchange
import org.springframework.web.service.annotation.PostExchange

@HttpExchange("/api/tika")
interface ITikaClient {
    @PostExchange("/process")
    suspend fun process(
        @RequestBody request: TikaProcessRequest,
    ): TikaProcessResult
}
