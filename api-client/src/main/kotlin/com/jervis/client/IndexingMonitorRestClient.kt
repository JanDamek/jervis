package com.jervis.client

import com.jervis.dto.AddLogRequest
import com.jervis.dto.FailIndexingRequest
import com.jervis.dto.StartIndexingRequest
import com.jervis.dto.UpdateStepRequest
import com.jervis.service.IIndexingMonitorService
import com.jervis.service.indexing.monitoring.ProjectIndexingState
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType

class IndexingMonitorRestClient(
    private val httpClient: HttpClient,
    private val baseUrl: String,
) : IIndexingMonitorService {
    private val apiPath = "$baseUrl/api/indexing-monitor"

    override suspend fun startProjectIndexing(request: StartIndexingRequest) {
        httpClient.post("$apiPath/start") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }
    }

    override suspend fun updateStepProgress(request: UpdateStepRequest) {
        httpClient.post("$apiPath/update-step") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }
    }

    override suspend fun addStepLog(request: AddLogRequest) {
        httpClient.post("$apiPath/add-log") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }
    }

    override suspend fun completeProjectIndexing(projectId: String) {
        httpClient.post("$apiPath/complete/$projectId")
    }

    override suspend fun failProjectIndexing(request: FailIndexingRequest) {
        httpClient.post("$apiPath/fail") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }
    }

    override fun getAllProjectStates(): Map<String, ProjectIndexingState> {
        // This method is synchronous in the interface, but REST calls are async
        // For now, return empty map - this should be called via suspend function
        return emptyMap()
    }

    suspend fun getAllProjectStatesAsync(): Map<String, ProjectIndexingState> = httpClient.get("$apiPath/states").body()
}
