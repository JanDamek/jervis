package com.jervis.coding.api

import com.jervis.coding.service.CodingEngineService
import com.jervis.common.client.ICodingEngineClient
import com.jervis.common.dto.CodingExecuteRequest
import com.jervis.common.dto.CodingExecuteResponse
import mu.KotlinLogging
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

private val logger = KotlinLogging.logger {}

@RestController
@RequestMapping("/api/coding")
class CodingEngineController(
    private val codingEngineService: CodingEngineService
) : ICodingEngineClient {

    @PostMapping(
        "/execute",
        consumes = [MediaType.APPLICATION_JSON_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    override suspend fun execute(@RequestBody req: CodingExecuteRequest): CodingExecuteResponse {
        logger.info { "CODING_EXECUTE: engine=${req.engine}, cid=${req.correlationId}, clientId=${req.clientId}, projectId=${req.projectId}" }

        return runCatching {
            when (req.engine.lowercase()) {
                "openhands" -> codingEngineService.executeOpenHands(req)
                else -> CodingExecuteResponse(
                    success = false,
                    engine = req.engine,
                    summary = "Unsupported engine: ${req.engine}",
                    details = "Supported engines: openhands",
                    metadata = mapOf("error" to "unsupported_engine")
                )
            }
        }.getOrElse { e ->
            logger.error(e) { "Coding engine execution failed: ${req.correlationId}" }
            CodingExecuteResponse(
                success = false,
                engine = req.engine,
                summary = "Execution failed: ${e.message}",
                details = null,
                metadata = mapOf("error" to (e.message ?: "unknown"))
            )
        }
    }
}
