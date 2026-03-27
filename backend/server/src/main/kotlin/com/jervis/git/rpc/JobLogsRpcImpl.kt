package com.jervis.git.rpc

import com.jervis.dto.git.JobLogEventDto
import com.jervis.service.meeting.IJobLogsService
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mu.KotlinLogging
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

/**
 * JobLogsRpcImpl — kRPC relay for live K8s Job log streaming.
 *
 * Connects to Python /job-logs/{taskId} SSE endpoint and relays
 * parsed log events to UI via kRPC Flow.
 */
@Component
class JobLogsRpcImpl(
    @Value("\${endpoints.orchestrator.baseUrl:http://localhost:8090}") private val orchestratorBaseUrl: String,
) : IJobLogsService {
    private val logger = KotlinLogging.logger {}

    private val client = HttpClient(CIO) {
        install(HttpTimeout) {
            requestTimeoutMillis = Long.MAX_VALUE
            connectTimeoutMillis = 30_000
            socketTimeoutMillis = Long.MAX_VALUE
        }
    }

    private val jsonParser = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    override fun subscribeToJobLogs(taskId: String): Flow<JobLogEventDto> = flow {
        val apiUrl = "${orchestratorBaseUrl.trimEnd('/')}/job-logs/$taskId"
        logger.info { "JOB_LOGS_SUBSCRIBE | taskId=$taskId | url=$apiUrl" }

        try {
            client.prepareGet(apiUrl).execute { response ->
                val channel = response.bodyAsChannel()

                while (!channel.isClosedForRead) {
                    val line = channel.readUTF8Line() ?: break

                    if (line.startsWith("data:")) {
                        val data = line.removePrefix("data:").trim()
                        if (data.isNotEmpty()) {
                            val event = parseLogEvent(data)
                            if (event != null) {
                                emit(event)
                                if (event.type == "done" || event.type == "error") {
                                    return@execute
                                }
                            }
                        }
                    }
                }
            }

            // Stream ended normally
            emit(JobLogEventDto(type = "done", content = "Log stream ended"))
        } catch (e: Exception) {
            logger.error(e) { "JOB_LOGS_ERROR | taskId=$taskId" }
            emit(JobLogEventDto(type = "error", content = "Failed to stream logs: ${e.message}"))
        }
    }

    private fun parseLogEvent(data: String): JobLogEventDto? {
        return try {
            val json = jsonParser.parseToJsonElement(data).jsonObject
            val type = json["type"]?.jsonPrimitive?.contentOrNull ?: "text"
            val content = json["content"]?.jsonPrimitive?.contentOrNull ?: ""
            val tool = json["tool"]?.jsonPrimitive?.contentOrNull ?: ""
            JobLogEventDto(type = type, content = content, tool = tool)
        } catch (e: Exception) {
            logger.debug { "Failed to parse log event: ${data.take(200)}" }
            null
        }
    }
}
