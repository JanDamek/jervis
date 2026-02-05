package com.jervis.common.client

import com.jervis.common.dto.atlassian.AtlassianMyselfRequest
import com.jervis.common.dto.atlassian.AtlassianUserDto
import com.jervis.common.dto.atlassian.ConfluenceAttachmentDownloadRequest
import com.jervis.common.dto.atlassian.ConfluencePageRequest
import com.jervis.common.dto.atlassian.ConfluencePageResponse
import com.jervis.common.dto.atlassian.ConfluenceSearchRequest
import com.jervis.common.dto.atlassian.ConfluenceSearchResponse
import com.jervis.common.dto.atlassian.JiraAttachmentDownloadRequest
import com.jervis.common.dto.atlassian.JiraIssueRequest
import com.jervis.common.dto.atlassian.JiraIssueResponse
import com.jervis.common.dto.atlassian.JiraSearchRequest
import com.jervis.common.dto.atlassian.JiraSearchResponse
import kotlinx.rpc.annotations.Rpc

@Rpc
interface IAtlassianClient {
    suspend fun getMyself(request: AtlassianMyselfRequest): AtlassianUserDto

    suspend fun searchJiraIssues(request: JiraSearchRequest): JiraSearchResponse

    suspend fun getJiraIssue(request: JiraIssueRequest): JiraIssueResponse

    suspend fun searchConfluencePages(request: ConfluenceSearchRequest): ConfluenceSearchResponse

    suspend fun getConfluencePage(request: ConfluencePageRequest): ConfluencePageResponse

    suspend fun downloadJiraAttachment(request: JiraAttachmentDownloadRequest): ByteArray?

    suspend fun downloadConfluenceAttachment(request: ConfluenceAttachmentDownloadRequest): ByteArray?
}
