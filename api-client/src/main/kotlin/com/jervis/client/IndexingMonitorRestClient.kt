package com.jervis.client

import com.jervis.service.IIndexingMonitorService
import com.jervis.service.indexing.monitoring.IndexingProgress
import com.jervis.service.indexing.monitoring.IndexingProgressEvent
import com.jervis.service.indexing.monitoring.IndexingStepStatus
import com.jervis.service.indexing.monitoring.IndexingStepType
import com.jervis.service.indexing.monitoring.ProjectIndexingState
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.bson.types.ObjectId

class IndexingMonitorRestClient(
    private val httpClient: HttpClient,
    private val baseUrl: String,
) : IIndexingMonitorService {
    private val apiPath = "$baseUrl/api/indexing-monitor"

    override val progressFlow: Flow<IndexingProgressEvent>
        get() =
            flow {
                // For REST client, progressFlow is not supported (would require WebSocket or SSE)
                // This is a placeholder that emits nothing
                // Real-time updates would need a different transport mechanism
            }

    override suspend fun startProjectIndexing(
        projectId: ObjectId,
        projectName: String,
    ) {
        httpClient.post("$apiPath/start") {
            contentType(ContentType.Application.Json)
            setBody(
                mapOf(
                    "projectId" to projectId.toHexString(),
                    "projectName" to projectName,
                ),
            )
        }
    }

    override suspend fun updateStepProgress(
        projectId: ObjectId,
        stepType: IndexingStepType,
        status: IndexingStepStatus,
        progress: IndexingProgress?,
        message: String?,
        errorMessage: String?,
        logs: List<String>,
    ) {
        httpClient.post("$apiPath/update-step") {
            contentType(ContentType.Application.Json)
            setBody(
                mapOf(
                    "projectId" to projectId.toHexString(),
                    "stepType" to stepType.name,
                    "status" to status.name,
                    "progress" to progress,
                    "message" to message,
                    "errorMessage" to errorMessage,
                    "logs" to logs,
                ),
            )
        }
    }

    override suspend fun addStepLog(
        projectId: ObjectId,
        stepType: IndexingStepType,
        logMessage: String,
    ) {
        httpClient.post("$apiPath/add-log") {
            contentType(ContentType.Application.Json)
            setBody(
                mapOf(
                    "projectId" to projectId.toHexString(),
                    "stepType" to stepType.name,
                    "logMessage" to logMessage,
                ),
            )
        }
    }

    override suspend fun completeProjectIndexing(projectId: ObjectId) {
        httpClient.post("$apiPath/complete/${projectId.toHexString()}")
    }

    override suspend fun failProjectIndexing(
        projectId: ObjectId,
        errorMessage: String,
    ) {
        httpClient.post("$apiPath/fail") {
            contentType(ContentType.Application.Json)
            setBody(
                mapOf(
                    "projectId" to projectId.toHexString(),
                    "errorMessage" to errorMessage,
                ),
            )
        }
    }

    override fun getAllProjectStates(): Map<ObjectId, ProjectIndexingState> {
        // This method is synchronous in the interface, but REST calls are async
        // For now, return empty map - this should be called via suspend function
        return emptyMap()
    }

    suspend fun getAllProjectStatesAsync(): Map<String, ProjectIndexingState> = httpClient.get("$apiPath/states").body()
}
