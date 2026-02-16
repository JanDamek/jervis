package com.jervis.atlassian.service

import com.jervis.common.client.IAtlassianClient
import com.jervis.common.client.IBugTrackerClient
import com.jervis.common.client.IWikiClient
import com.jervis.common.dto.atlassian.*
import com.jervis.common.dto.bugtracker.*
import com.jervis.common.dto.wiki.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

class AtlassianServiceImpl(
    private val atlassianApiClient: AtlassianApiClient,
) : IAtlassianClient,
    IBugTrackerClient,
    IWikiClient {
    // --- IAtlassianClient ---

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

    // --- IBugTrackerClient ---

    override suspend fun getUser(request: BugTrackerUserRequest): BugTrackerUserDto =
        withContext(Dispatchers.IO) {
            logger.info { "BugTracker: getUser request for baseUrl=${request.baseUrl}" }
            val atlassianUser =
                atlassianApiClient.getMyself(
                    AtlassianMyselfRequest(
                        baseUrl = request.baseUrl,
                        authType = request.authType,
                        basicUsername = request.basicUsername,
                        basicPassword = request.basicPassword,
                        bearerToken = request.bearerToken,
                        cloudId = request.cloudId,
                    ),
                )
            BugTrackerUserDto(
                id = atlassianUser.accountId ?: "",
                username = atlassianUser.emailAddress ?: "",
                displayName = atlassianUser.displayName ?: "",
                email = atlassianUser.emailAddress,
            )
        }

    override suspend fun searchIssues(request: BugTrackerSearchRequest): BugTrackerSearchResponse =
        withContext(Dispatchers.IO) {
            logger.info { "BugTracker: searchIssues request: query=${request.query}" }
            val response =
                atlassianApiClient.searchJiraIssues(
                    JiraSearchRequest(
                        baseUrl = request.baseUrl,
                        authType = request.authType,
                        basicUsername = request.basicUsername,
                        basicPassword = request.basicPassword,
                        bearerToken = request.bearerToken,
                        cloudId = request.cloudId,
                        jql = request.query ?: "",
                        maxResults = request.maxResults,
                    ),
                )
            BugTrackerSearchResponse(
                issues = response.issues.map { it.toBugTrackerIssueDto(request.baseUrl) },
                total = response.total,
            )
        }

    override suspend fun getIssue(request: BugTrackerIssueRequest): BugTrackerIssueResponse =
        withContext(Dispatchers.IO) {
            logger.info { "BugTracker: getIssue request: issueKey=${request.issueKey}" }
            val response =
                atlassianApiClient.getJiraIssue(
                    JiraIssueRequest(
                        baseUrl = request.baseUrl,
                        authType = request.authType,
                        basicUsername = request.basicUsername,
                        basicPassword = request.basicPassword,
                        bearerToken = request.bearerToken,
                        cloudId = request.cloudId,
                        issueKey = request.issueKey,
                    ),
                )
            BugTrackerIssueResponse(
                issue = response.toBugTrackerIssueDto(request.baseUrl),
            )
        }

    override suspend fun listProjects(request: BugTrackerProjectsRequest): BugTrackerProjectsResponse =
        withContext(Dispatchers.IO) {
            logger.info { "BugTracker: listProjects request for baseUrl=${request.baseUrl}" }
            atlassianApiClient.listJiraProjects(request)
        }

    override suspend fun createIssue(request: BugTrackerCreateIssueRpcRequest): BugTrackerIssueResponse =
        withContext(Dispatchers.IO) {
            logger.info { "BugTracker: createIssue in project=${request.projectKey}, summary=${request.summary}" }
            atlassianApiClient.createJiraIssue(
                baseUrl = request.baseUrl,
                authType = request.authType,
                basicUsername = request.basicUsername,
                basicPassword = request.basicPassword,
                bearerToken = request.bearerToken,
                cloudId = request.cloudId,
                projectKey = request.projectKey,
                summary = request.summary,
                description = request.description,
                issueType = request.issueType,
                priority = request.priority,
                assignee = request.assignee,
                labels = request.labels,
                epicKey = request.epicKey,
            )
        }

    override suspend fun updateIssue(request: BugTrackerUpdateIssueRpcRequest): BugTrackerIssueResponse =
        withContext(Dispatchers.IO) {
            logger.info { "BugTracker: updateIssue key=${request.issueKey}" }
            atlassianApiClient.updateJiraIssue(
                baseUrl = request.baseUrl,
                authType = request.authType,
                basicUsername = request.basicUsername,
                basicPassword = request.basicPassword,
                bearerToken = request.bearerToken,
                cloudId = request.cloudId,
                issueKey = request.issueKey,
                summary = request.summary,
                description = request.description,
                assignee = request.assignee,
                priority = request.priority,
                labels = request.labels,
            )
        }

    override suspend fun addComment(request: BugTrackerAddCommentRpcRequest): BugTrackerCommentResponse =
        withContext(Dispatchers.IO) {
            logger.info { "BugTracker: addComment to issue=${request.issueKey}" }
            atlassianApiClient.addJiraComment(
                baseUrl = request.baseUrl,
                authType = request.authType,
                basicUsername = request.basicUsername,
                basicPassword = request.basicPassword,
                bearerToken = request.bearerToken,
                cloudId = request.cloudId,
                issueKey = request.issueKey,
                commentBody = request.body,
            )
        }

    override suspend fun transitionIssue(request: BugTrackerTransitionRpcRequest) =
        withContext(Dispatchers.IO) {
            logger.info { "BugTracker: transitionIssue key=${request.issueKey} to ${request.transitionName}" }
            atlassianApiClient.transitionJiraIssue(
                baseUrl = request.baseUrl,
                authType = request.authType,
                basicUsername = request.basicUsername,
                basicPassword = request.basicPassword,
                bearerToken = request.bearerToken,
                cloudId = request.cloudId,
                issueKey = request.issueKey,
                transitionName = request.transitionName,
            )
        }

    // --- IWikiClient ---

    override suspend fun getUser(request: WikiUserRequest): WikiUserDto =
        withContext(Dispatchers.IO) {
            logger.info { "Wiki: getUser request for baseUrl=${request.baseUrl}" }
            val atlassianUser =
                atlassianApiClient.getMyself(
                    AtlassianMyselfRequest(
                        baseUrl = request.baseUrl,
                        authType = request.authType,
                        basicUsername = request.basicUsername,
                        basicPassword = request.basicPassword,
                        bearerToken = request.bearerToken,
                        cloudId = request.cloudId,
                    ),
                )
            WikiUserDto(
                id = atlassianUser.accountId ?: "",
                username = atlassianUser.emailAddress ?: "",
                displayName = atlassianUser.displayName ?: "",
                email = atlassianUser.emailAddress,
            )
        }

    override suspend fun searchPages(request: WikiSearchRequest): WikiSearchResponse =
        withContext(Dispatchers.IO) {
            logger.info { "Wiki: searchPages request: query=${request.query}" }
            val response =
                atlassianApiClient.searchConfluencePages(
                    ConfluenceSearchRequest(
                        baseUrl = request.baseUrl,
                        authType = request.authType,
                        basicUsername = request.basicUsername,
                        basicPassword = request.basicPassword,
                        bearerToken = request.bearerToken,
                        cloudId = request.cloudId,
                        spaceKey = request.spaceKey,
                        cql = request.query,
                        maxResults = request.maxResults,
                    ),
                )
            WikiSearchResponse(
                pages = response.pages.map { it.toWikiPageDto(request.baseUrl) },
                total = response.total,
            )
        }

    override suspend fun getPage(request: WikiPageRequest): WikiPageResponse =
        withContext(Dispatchers.IO) {
            logger.info { "Wiki: getPage request: pageId=${request.pageId}" }
            val response =
                atlassianApiClient.getConfluencePage(
                    ConfluencePageRequest(
                        baseUrl = request.baseUrl,
                        authType = request.authType,
                        basicUsername = request.basicUsername,
                        basicPassword = request.basicPassword,
                        bearerToken = request.bearerToken,
                        cloudId = request.cloudId,
                        pageId = request.pageId,
                    ),
                )
            WikiPageResponse(
                page = response.toWikiPageDto(request.baseUrl),
            )
        }

    override suspend fun listSpaces(request: WikiSpacesRequest): WikiSpacesResponse =
        withContext(Dispatchers.IO) {
            logger.info { "Wiki: listSpaces request for baseUrl=${request.baseUrl}" }
            atlassianApiClient.listConfluenceSpaces(request)
        }

    override suspend fun createPage(request: WikiCreatePageRpcRequest): WikiPageResponse =
        withContext(Dispatchers.IO) {
            logger.info { "Wiki: createPage in space=${request.spaceKey}, title=${request.title}" }
            atlassianApiClient.createConfluencePage(
                baseUrl = request.baseUrl,
                authType = request.authType,
                basicUsername = request.basicUsername,
                basicPassword = request.basicPassword,
                bearerToken = request.bearerToken,
                cloudId = request.cloudId,
                spaceKey = request.spaceKey,
                title = request.title,
                content = request.content,
                parentPageId = request.parentPageId,
            )
        }

    override suspend fun updatePage(request: WikiUpdatePageRpcRequest): WikiPageResponse =
        withContext(Dispatchers.IO) {
            logger.info { "Wiki: updatePage id=${request.pageId}, title=${request.title}" }
            atlassianApiClient.updateConfluencePage(
                baseUrl = request.baseUrl,
                authType = request.authType,
                basicUsername = request.basicUsername,
                basicPassword = request.basicPassword,
                bearerToken = request.bearerToken,
                cloudId = request.cloudId,
                pageId = request.pageId,
                title = request.title,
                content = request.content,
                version = request.version,
            )
        }

    // --- Helpers ---

    private fun JiraIssueSummary.toBugTrackerIssueDto(baseUrl: String) =
        BugTrackerIssueDto(
            id = this.id,
            key = this.key,
            title = this.fields.summary ?: "",
            description = this.fields.description?.toString(),
            status = this.fields.status?.name ?: "",
            priority = this.fields.priority?.name,
            assignee = this.fields.assignee?.displayName,
            reporter = this.fields.reporter?.displayName,
            created = this.fields.created ?: "",
            updated = this.fields.updated ?: "",
            url = "${baseUrl.trimEnd('/')}/browse/${this.key}",
            projectKey = this.fields.project?.key,
        )

    private fun JiraIssueResponse.toBugTrackerIssueDto(baseUrl: String) =
        BugTrackerIssueDto(
            id = this.id,
            key = this.key,
            title = this.fields.summary ?: "",
            description = this.renderedDescription ?: this.fields.description?.toString(),
            status = this.fields.status?.name ?: "",
            priority = this.fields.priority?.name,
            assignee = this.fields.assignee?.displayName,
            reporter = this.fields.reporter?.displayName,
            created = this.fields.created ?: "",
            updated = this.fields.updated ?: "",
            url = "${baseUrl.trimEnd('/')}/browse/${this.key}",
            projectKey = this.fields.project?.key,
        )

    private fun ConfluencePageSummary.toWikiPageDto(baseUrl: String) =
        WikiPageDto(
            id = this.id,
            title = this.title,
            content = this.body?.storage?.value ?: this.body?.view?.value,
            spaceKey = this.spaceKey,
            url = "${baseUrl.trimEnd('/')}/wiki/spaces/${this.spaceKey}/pages/${this.id}",
            created = this.createdDate ?: "",
            updated = this.lastModified ?: "",
        )

    private fun ConfluencePageResponse.toWikiPageDto(baseUrl: String) =
        WikiPageDto(
            id = this.id,
            title = this.title,
            content = this.body?.storage?.value ?: this.body?.view?.value,
            spaceKey = this.spaceKey,
            url = "${baseUrl.trimEnd('/')}/wiki/spaces/${this.spaceKey}/pages/${this.id}",
            created = this.createdDate ?: "",
            updated = this.lastModified ?: "",
        )
}
