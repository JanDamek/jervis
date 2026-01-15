package com.jervis.atlassian.api

import com.jervis.atlassian.service.AtlassianApiClient
import com.jervis.common.client.IAtlassianClient
import com.jervis.common.dto.atlassian.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.rpc.krpc.ktor.server.*
import kotlinx.rpc.krpc.serialization.cbor.cbor
import mu.KotlinLogging

class AtlassianController(
    private val atlassianApiClient: AtlassianApiClient,
    private val port: Int = 8080,
) : IAtlassianClient {
    private val logger = KotlinLogging.logger {}

    fun startRpcServer() {
        embeddedServer(Netty, port = port, host = "0.0.0.0") {
            routing {
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
