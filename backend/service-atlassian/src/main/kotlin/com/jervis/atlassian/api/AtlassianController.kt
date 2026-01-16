package com.jervis.atlassian.api

import com.jervis.atlassian.service.AtlassianApiClient
import com.jervis.common.client.IAtlassianClient
import com.jervis.common.dto.atlassian.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.response.*
import io.ktor.server.request.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.rpc.krpc.ktor.server.*
import kotlinx.rpc.krpc.serialization.cbor.cbor
import kotlinx.serialization.json.Json
import mu.KotlinLogging

class AtlassianController(
    private val atlassianApiClient: AtlassianApiClient,
    private val port: Int = 8080,
) : IAtlassianClient {
    private val logger = KotlinLogging.logger {}

    fun startRpcServer() {
        embeddedServer(Netty, port = port, host = "0.0.0.0") {
            install(WebSockets)
            install(ContentNegotiation) {
                json(Json {
                    ignoreUnknownKeys = true
                    isLenient = true
                    explicitNulls = false
                })
            }
            routing {
                get("/health") {
                    call.respondText("{\"status\":\"UP\"}", io.ktor.http.ContentType.Application.Json)
                }
                get("/actuator/health") {
                    call.respondText("{\"status\":\"UP\"}", io.ktor.http.ContentType.Application.Json)
                }

                post("/api/atlassian/myself") {
                    val req = call.receive<AtlassianMyselfRequest>()
                    call.respond(getMyself(req))
                }
                post("/api/atlassian/jira/search") {
                    val req = call.receive<JiraSearchRequest>()
                    call.respond(searchJiraIssues(req))
                }
                post("/api/atlassian/jira/issue") {
                    val req = call.receive<JiraIssueRequest>()
                    call.respond(getJiraIssue(req))
                }
                post("/api/atlassian/confluence/search") {
                    val req = call.receive<ConfluenceSearchRequest>()
                    call.respond(searchConfluencePages(req))
                }
                post("/api/atlassian/confluence/page") {
                    val req = call.receive<ConfluencePageRequest>()
                    call.respond(getConfluencePage(req))
                }
                post("/api/atlassian/jira/attachment") {
                    val req = call.receive<JiraAttachmentDownloadRequest>()
                    val data = downloadJiraAttachment(req)
                    if (data != null) call.respond(data) else call.respond(HttpStatusCode.NotFound)
                }
                post("/api/atlassian/confluence/attachment") {
                    val req = call.receive<ConfluenceAttachmentDownloadRequest>()
                    val data = downloadConfluenceAttachment(req)
                    if (data != null) call.respond(data) else call.respond(HttpStatusCode.NotFound)
                }

                get("/") {
                    call.respondText("{\"status\":\"UP\"}", io.ktor.http.ContentType.Application.Json)
                }

                rpc("/rpc") {
                    rpcConfig {
                        serialization {
                            cbor()
                        }
                    }
                    registerService<IAtlassianClient> { this@AtlassianController }
                }
            }
        }.start(wait = true)
    }

    override suspend fun getMyself(
        request: AtlassianMyselfRequest,
    ): AtlassianUserDto =
        withContext(Dispatchers.IO) {
            atlassianApiClient.getMyself(request)
        }

    override suspend fun searchJiraIssues(
        request: JiraSearchRequest,
    ): JiraSearchResponse =
        withContext(Dispatchers.IO) {
            atlassianApiClient.searchJiraIssues(request)
        }

    override suspend fun getJiraIssue(
        request: JiraIssueRequest,
    ): JiraIssueResponse =
        withContext(Dispatchers.IO) {
            atlassianApiClient.getJiraIssue(request)
        }

    override suspend fun searchConfluencePages(
        request: ConfluenceSearchRequest,
    ): ConfluenceSearchResponse =
        withContext(Dispatchers.IO) {
            atlassianApiClient.searchConfluencePages(request)
        }

    override suspend fun getConfluencePage(
        request: ConfluencePageRequest,
    ): ConfluencePageResponse =
        withContext(Dispatchers.IO) {
            atlassianApiClient.getConfluencePage(request)
        }

    override suspend fun downloadJiraAttachment(
        request: JiraAttachmentDownloadRequest,
    ): ByteArray? =
        withContext(Dispatchers.IO) {
            atlassianApiClient.downloadJiraAttachment(request)
        }

    override suspend fun downloadConfluenceAttachment(
        request: ConfluenceAttachmentDownloadRequest,
    ): ByteArray? =
        withContext(Dispatchers.IO) {
            atlassianApiClient.downloadConfluenceAttachment(request)
        }
}
