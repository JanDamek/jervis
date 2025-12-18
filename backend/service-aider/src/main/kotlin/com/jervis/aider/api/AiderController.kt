package com.jervis.aider.api

import com.jervis.aider.service.AiderService
import com.jervis.common.client.IAiderClient
import com.jervis.common.dto.CodingExecuteRequest
import com.jervis.common.dto.CodingExecuteResponse
import mu.KotlinLogging
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

private val logger = KotlinLogging.logger {}

@RestController
class AiderController(
    private val aiderService: AiderService,
) : IAiderClient {
    override suspend fun execute(
        @RequestBody request: CodingExecuteRequest,
    ): CodingExecuteResponse {
        logger.info { "AIDER_RUN: cid=${request.correlationId}, clientId=${request.clientId}, projectId=${request.projectId}" }

        return runCatching {
            aiderService.executeAider(request)
        }.getOrElse { e ->
            when (e) {
                is SecurityException -> {
                    logger.error(e) { "Security validation failed: ${request.correlationId}" }
                    CodingExecuteResponse(
                        success = false,
                        summary = "Security validation failed: ${e.message}",
                        details = null
                    )
                }

                else -> {
                    logger.error(e) { "Aider execution failed: ${request.correlationId}" }
                    CodingExecuteResponse(
                        success = false,
                        summary = "Execution failed: ${e.message}",
                        details = null
                    )
                }
            }
        }
    }
}
