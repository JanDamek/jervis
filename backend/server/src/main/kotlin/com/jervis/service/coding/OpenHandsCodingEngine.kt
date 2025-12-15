package com.jervis.service.coding

import com.jervis.configuration.KtorClientFactory
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.Serializable
import mu.KotlinLogging
import org.springframework.stereotype.Service

@Service
class OpenHandsCodingEngine(
    private val ktorClientFactory: KtorClientFactory,
) : CodingEngine {
    private val logger = KotlinLogging.logger {}

    @Serializable
    data class SubmitRequest(
        val task: String,
        val agent_cls: String = "CodeActAgent",
        val model: String = "ollama/qwen3-coder-tool:30b",
        val correlationId: String? = null,
        val repoUrl: String? = null,
    )

    @Serializable
    data class SubmitResponse(
        val jobId: String,
        val status: String? = null,
        val message: String? = null,
    )

    @Serializable
    data class StatusResponse(
        val jobId: String,
        val status: String,
        val result: String? = null,
        val error: String? = null,
    )

    override suspend fun execute(request: CodingRequest): CodingResult {
        val client = ktorClientFactory.getHttpClient("openhands")
        val payload =
            SubmitRequest(
                task = request.taskDescription,
                correlationId = request.correlationId,
                repoUrl = request.extra["repoUrl"],
                model = request.extra["model"] ?: "ollama/qwen3-coder-tool:30b",
            )

        return try {
            val resp: SubmitResponse =
                client
                    .post("/api/submit-task") {
                        contentType(ContentType.Application.Json)
                        setBody(payload)
                    }.body()

            CodingResult(
                success = true,
                summary = "OpenHands job submitted: ${resp.jobId}",
                details = resp.message,
                engine = "openhands",
                metadata = mapOf("jobId" to resp.jobId, "status" to (resp.status ?: "SUBMITTED")),
            )
        } catch (e: Exception) {
            logger.error(e) { "OpenHands submission failed: ${e.message}" }
            CodingResult(
                success = false,
                summary = "OpenHands submission failed: ${e.message}",
                details = e.stackTraceToString(),
                engine = "openhands",
            )
        }
    }

    suspend fun checkStatus(jobId: String): StatusResponse {
        val client = ktorClientFactory.getHttpClient("openhands")
        return client.get("/api/task-status/$jobId") {}.body()
    }
}
