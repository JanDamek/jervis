package com.jervis.common.client

import com.jervis.common.dto.atlassian.*
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
