package com.jervis.coding.api

import com.jervis.coding.service.CodingEngineService
import com.jervis.common.client.ICodingEngineClient
import com.jervis.common.dto.CodingExecuteRequest
import com.jervis.common.dto.CodingExecuteResponse
import mu.KotlinLogging
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

private val logger = KotlinLogging.logger {}

@RestController
class CodingEngineController(
    private val codingEngineService: CodingEngineService,
) : ICodingEngineClient {
    override suspend fun execute(
        @RequestBody request: CodingExecuteRequest,
    ): CodingExecuteResponse {
        logger.info { "CODING_EXECUTE: cid=${request.correlationId}, clientId=${request.clientId}, projectId=${request.projectId}" }

        return runCatching {
            codingEngineService.executeOpenHands(request)
        }.getOrElse { e ->
            logger.error(e) { "Coding engine execution failed: ${request.correlationId}" }
            CodingExecuteResponse(
                success = false,
                summary = "Execution failed: ${e.message}",
                details = null,
            )
        }
    }
}
