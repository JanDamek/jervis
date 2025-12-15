package com.jervis.aider.api

import com.jervis.aider.service.AiderService
import com.jervis.common.client.IAiderClient
import com.jervis.common.dto.AiderRunRequest
import com.jervis.common.dto.AiderRunResponse
import com.jervis.common.dto.AiderStatusResponse
import mu.KotlinLogging
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

private val logger = KotlinLogging.logger {}

@RestController
@RequestMapping("/api/aider")
class AiderController(
    private val aiderService: AiderService
) : IAiderClient {

    @PostMapping("/run", consumes = [MediaType.APPLICATION_JSON_VALUE], produces = [MediaType.APPLICATION_JSON_VALUE])
    override suspend fun run(
        @RequestBody request: AiderRunRequest,
        @RequestParam(name = "async", required = false, defaultValue = "false") async: Boolean,
    ): AiderRunResponse {
        logger.info { "AIDER_RUN: cid=${request.correlationId}, async=$async, clientId=${request.clientId}, projectId=${request.projectId}" }

        return runCatching {
            aiderService.executeAider(request)
        }.getOrElse { e ->
            when (e) {
                is SecurityException -> {
                    logger.error(e) { "Security validation failed: ${request.correlationId}" }
                    AiderRunResponse(
                        success = false,
                        output = "",
                        message = "Security validation failed: ${e.message}",
                        status = "FAILED"
                    )
                }
                else -> {
                    logger.error(e) { "Aider execution failed: ${request.correlationId}" }
                    AiderRunResponse(
                        success = false,
                        output = "",
                        message = "Execution failed: ${e.message}",
                        status = "FAILED"
                    )
                }
            }
        }
    }

    @GetMapping("/status/{jobId}", produces = [MediaType.APPLICATION_JSON_VALUE])
    override suspend fun status(@PathVariable jobId: String): AiderStatusResponse {
        logger.info { "AIDER_STATUS: jobId=$jobId" }
        return aiderService.getJobStatus(jobId)
            ?: AiderStatusResponse(jobId = jobId, status = "NOT_FOUND", error = "Unknown jobId")
    }
}
