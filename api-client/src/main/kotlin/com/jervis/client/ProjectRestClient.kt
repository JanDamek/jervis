package com.jervis.client

import com.jervis.dto.ProjectDto
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

    override suspend fun getAllProjects(): List<ProjectDto> = httpClient.get(apiPath).body()

    override suspend fun getDefaultProject(): ProjectDto? = httpClient.get("$apiPath/default").body()

    override suspend fun setActiveProject(project: ProjectDto) {
        httpClient.put("$apiPath/active") {
            contentType(ContentType.Application.Json)
            setBody(project)
        }
    }

    override suspend fun setDefaultProject(project: ProjectDto) {
        httpClient.put("$apiPath/default") {
            contentType(ContentType.Application.Json)
            setBody(project)
        }
    }

    override suspend fun saveProject(
        project: ProjectDto,
        makeDefault: Boolean,
    ): ProjectDto =
        httpClient
            .post(apiPath) {
                contentType(ContentType.Application.Json)
                parameter("makeDefault", makeDefault)
                setBody(project)
            }.body()

    override suspend fun deleteProject(project: ProjectDto) {
        httpClient.delete(apiPath) {
            contentType(ContentType.Application.Json)
            setBody(project)
        }
    }

    override suspend fun getProjectByName(name: String?): ProjectDto =
        httpClient
            .get("$apiPath/by-name") {
                parameter("name", name)
            }.body()
}
