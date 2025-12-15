package com.jervis.common.client

import com.jervis.common.dto.AiderRunRequest
import com.jervis.common.dto.AiderRunResponse
import com.jervis.common.dto.AiderStatusResponse
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.service.annotation.GetExchange
import org.springframework.web.service.annotation.HttpExchange
import org.springframework.web.service.annotation.PostExchange

@HttpExchange("/api/aider")
interface IAiderClient {
    @PostExchange("/run")
    suspend fun run(
        @RequestBody request: AiderRunRequest,
        @RequestParam(name = "async", required = false) async: Boolean = false,
    ): AiderRunResponse

    @GetExchange("/status/{jobId}")
    suspend fun status(
        @PathVariable jobId: String,
    ): AiderStatusResponse
}
