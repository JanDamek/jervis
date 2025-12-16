package com.jervis.atlassian.api

import com.jervis.atlassian.service.AtlassianApiClient
import com.jervis.common.client.IAtlassianClient
import com.jervis.common.dto.atlassian.AtlassianMyselfRequest
import com.jervis.common.dto.atlassian.AtlassianUserDto
import com.jervis.common.dto.atlassian.ConfluencePageRequest
import com.jervis.common.dto.atlassian.ConfluencePageResponse
import com.jervis.common.dto.atlassian.ConfluenceSearchRequest
import com.jervis.common.dto.atlassian.ConfluenceSearchResponse
import com.jervis.common.dto.atlassian.JiraAttachmentDownloadRequest
import com.jervis.common.dto.atlassian.JiraIssueRequest
import com.jervis.common.dto.atlassian.JiraIssueResponse
import com.jervis.common.dto.atlassian.JiraSearchRequest
import com.jervis.common.dto.atlassian.JiraSearchResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

@RestController
class AtlassianController(
    private val atlassianApiClient: AtlassianApiClient,
) : IAtlassianClient {
    override suspend fun getMyself(
        @RequestBody request: AtlassianMyselfRequest,
    ): AtlassianUserDto =
        withContext(Dispatchers.IO) {
            atlassianApiClient.getMyself(request)
        }

    override suspend fun searchJiraIssues(
        @RequestBody request: JiraSearchRequest,
    ): JiraSearchResponse =
        withContext(Dispatchers.IO) {
            atlassianApiClient.searchJiraIssues(request)
        }

    override suspend fun getJiraIssue(
        @RequestBody request: JiraIssueRequest,
    ): JiraIssueResponse =
        withContext(Dispatchers.IO) {
            atlassianApiClient.getJiraIssue(request)
        }

    override suspend fun searchConfluencePages(
        @RequestBody request: ConfluenceSearchRequest,
    ): ConfluenceSearchResponse =
        withContext(Dispatchers.IO) {
            atlassianApiClient.searchConfluencePages(request)
        }

    override suspend fun getConfluencePage(
        @RequestBody request: ConfluencePageRequest,
    ): ConfluencePageResponse =
        withContext(Dispatchers.IO) {
            atlassianApiClient.getConfluencePage(request)
        }

    override suspend fun downloadJiraAttachment(
        @RequestBody request: JiraAttachmentDownloadRequest,
    ): ByteArray =
        withContext(Dispatchers.IO) {
            atlassianApiClient.downloadJiraAttachment(request)
        }
}
