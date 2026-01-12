package com.jervis.common.client

import com.jervis.common.dto.atlassian.*
import de.jensklingenberg.ktorfit.http.Body
import de.jensklingenberg.ktorfit.http.POST

interface IAtlassianClient {
    @POST("api/atlassian/myself")
    suspend fun getMyself(
        @Body request: AtlassianMyselfRequest,
    ): AtlassianUserDto

    @POST("api/atlassian/jira/search")
    suspend fun searchJiraIssues(
        @Body request: JiraSearchRequest,
    ): JiraSearchResponse

    @POST("api/atlassian/jira/issue")
    suspend fun getJiraIssue(
        @Body request: JiraIssueRequest,
    ): JiraIssueResponse

    @POST("api/atlassian/confluence/search")
    suspend fun searchConfluencePages(
        @Body request: ConfluenceSearchRequest,
    ): ConfluenceSearchResponse

    @POST("api/atlassian/confluence/page")
    suspend fun getConfluencePage(
        @Body request: ConfluencePageRequest,
    ): ConfluencePageResponse

    @POST("api/atlassian/jira/attachment/download")
    suspend fun downloadJiraAttachment(
        @Body request: JiraAttachmentDownloadRequest,
    ): ByteArray?

    @POST("api/atlassian/confluence/attachment/download")
    suspend fun downloadConfluenceAttachment(
        @Body request: ConfluenceAttachmentDownloadRequest,
    ): ByteArray?
}
