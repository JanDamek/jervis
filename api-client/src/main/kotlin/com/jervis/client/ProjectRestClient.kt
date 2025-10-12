package com.jervis.client

import com.jervis.entity.mongo.ProjectDocument
import com.jervis.service.IProjectService
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType

class ProjectRestClient(
    private val httpClient: HttpClient,
    private val baseUrl: String,
) : IProjectService {
    private val apiPath = "$baseUrl/api/projects"

    override suspend fun getAllProjects(): List<ProjectDocument> = httpClient.get(apiPath).body()

    override suspend fun getDefaultProject(): ProjectDocument? = httpClient.get("$apiPath/default").body()

    override suspend fun setActiveProject(project: ProjectDocument) {
        httpClient.put("$apiPath/active") {
            contentType(ContentType.Application.Json)
            setBody(project)
        }
    }

    override suspend fun setDefaultProject(project: ProjectDocument) {
        httpClient.put("$apiPath/default") {
            contentType(ContentType.Application.Json)
            setBody(project)
        }
    }

    override suspend fun saveProject(
        project: ProjectDocument,
        makeDefault: Boolean,
    ): ProjectDocument =
        httpClient
            .post(apiPath) {
                contentType(ContentType.Application.Json)
                parameter("makeDefault", makeDefault)
                setBody(project)
            }.body()

    override suspend fun deleteProject(project: ProjectDocument) {
        httpClient.delete(apiPath) {
            contentType(ContentType.Application.Json)
            setBody(project)
        }
    }

    override suspend fun getProjectByName(name: String?): ProjectDocument =
        httpClient
            .get("$apiPath/by-name") {
                parameter("name", name)
            }.body()
}
