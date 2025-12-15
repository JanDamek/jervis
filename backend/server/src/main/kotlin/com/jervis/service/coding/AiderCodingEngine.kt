package com.jervis.service.coding

import com.jervis.common.client.IAiderClient
import com.jervis.common.dto.AiderRunRequest
import com.jervis.common.dto.AiderRunResponse
import com.jervis.common.dto.AiderStatusResponse
import mu.KotlinLogging
import org.springframework.stereotype.Service

@Service
class AiderCodingEngine(
    private val aiderClient: IAiderClient,
) : CodingEngine {
    private val logger = KotlinLogging.logger {}

    data class RunResponse(
        val success: Boolean,
        val output: String,
        val message: String? = null,
        val jobId: String? = null,
        val status: String? = null,
    )

    override suspend fun execute(request: CodingRequest): CodingResult {
        val payload =
            AiderRunRequest(
                correlationId = request.correlationId,
                clientId = request.clientId.toString(),
                projectId = request.projectId?.toString(),
                taskDescription = request.taskDescription,
                targetFiles = request.targetFiles,
                model = request.extra["model"],
            )

        return try {
            val resp: AiderRunResponse = aiderClient.run(payload, async = false)

            if (resp.success) {
                CodingResult(
                    success = true,
                    summary = "Aider completed successfully",
                    details = resp.output.takeLast(10_000),
                    engine = "aider",
                    metadata =
                        buildMap {
                            resp.jobId?.let { put("jobId", it) }
                            resp.status?.let { put("status", it) }
                        },
                )
            } else {
                CodingResult(
                    success = false,
                    summary = resp.message ?: "Aider reported failure",
                    details = resp.output.takeLast(10_000),
                    engine = "aider",
                    metadata =
                        buildMap {
                            resp.jobId?.let { put("jobId", it) }
                            resp.status?.let { put("status", it) }
                        },
                )
            }
        } catch (e: Exception) {
            logger.error(e) { "Aider service call failed: ${e.message}" }
            CodingResult(
                success = false,
                summary = "Aider service call failed: ${e.message}",
                details = e.stackTraceToString(),
                engine = "aider",
            )
        }
    }

    data class StatusResponse(
        val jobId: String,
        val status: String,
        val result: String? = null,
        val error: String? = null,
    )

    suspend fun checkStatus(jobId: String): StatusResponse {
        val resp: AiderStatusResponse = aiderClient.status(jobId)
        return StatusResponse(
            jobId = resp.jobId,
            status = resp.status,
            result = resp.result,
            error = resp.error,
        )
    }
}
