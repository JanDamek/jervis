package com.jervis.atlassian.service

import com.jervis.common.client.IAtlassianClient
import com.jervis.common.dto.atlassian.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

class AtlassianServiceImpl(
    private val atlassianApiClient: AtlassianApiClient,
) : IAtlassianClient {

    override suspend fun getMyself(request: AtlassianMyselfRequest): AtlassianUserDto =
        withContext(Dispatchers.IO) {
            logger.info { "getMyself request for baseUrl=${request.baseUrl}" }
            atlassianApiClient.getMyself(request)
        }

    override suspend fun searchJiraIssues(request: JiraSearchRequest): JiraSearchResponse =
        withContext(Dispatchers.IO) {
            logger.info { "searchJiraIssues request: jql=${request.jql}" }
            atlassianApiClient.searchJiraIssues(request)
        }

    override suspend fun getJiraIssue(request: JiraIssueRequest): JiraIssueResponse =
        withContext(Dispatchers.IO) {
            logger.info { "getJiraIssue request: issueKey=${request.issueKey}" }
            atlassianApiClient.getJiraIssue(request)
        }

    override suspend fun searchConfluencePages(request: ConfluenceSearchRequest): ConfluenceSearchResponse =
        withContext(Dispatchers.IO) {
            logger.info { "searchConfluencePages request: cql=${request.cql}" }
            atlassianApiClient.searchConfluencePages(request)
        }

    override suspend fun getConfluencePage(request: ConfluencePageRequest): ConfluencePageResponse =
        withContext(Dispatchers.IO) {
            logger.info { "getConfluencePage request: pageId=${request.pageId}" }
            atlassianApiClient.getConfluencePage(request)
        }

    override suspend fun downloadJiraAttachment(request: JiraAttachmentDownloadRequest): ByteArray? =
        withContext(Dispatchers.IO) {
            logger.info { "downloadJiraAttachment request: attachmentUrl=${request.attachmentUrl}" }
            atlassianApiClient.downloadJiraAttachment(request)
        }

    override suspend fun downloadConfluenceAttachment(request: ConfluenceAttachmentDownloadRequest): ByteArray? =
        withContext(Dispatchers.IO) {
            logger.info { "downloadConfluenceAttachment request: attachmentDownloadUrl=${request.attachmentDownloadUrl}" }
            atlassianApiClient.downloadConfluenceAttachment(request)
        }
}
