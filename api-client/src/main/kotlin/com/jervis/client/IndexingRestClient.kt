package com.jervis.client

import com.jervis.entity.mongo.ProjectDocument
import com.jervis.service.IIndexingService
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType

class IndexingRestClient(
    private val httpClient: HttpClient,
    private val baseUrl: String,
) : IIndexingService {
    private val apiPath = "$baseUrl/api/indexing"

    override suspend fun indexProject(project: ProjectDocument): Any =
        httpClient
            .post("$apiPath/project") {
                contentType(ContentType.Application.Json)
                setBody(project)
            }.body()

    override suspend fun indexAllProjects(projects: List<ProjectDocument>) {
        httpClient.post("$apiPath/all-projects") {
            contentType(ContentType.Application.Json)
            setBody(projects)
        }
    }

    override suspend fun indexProjectsForClient(
        projects: List<ProjectDocument>,
        clientName: String,
    ) {
        httpClient.post("$apiPath/client-projects") {
            contentType(ContentType.Application.Json)
            parameter("clientName", clientName)
            setBody(projects)
        }
    }
}
