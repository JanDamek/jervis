package com.jervis.service.brain

import com.jervis.common.client.IBugTrackerClient
import com.jervis.common.client.IWikiClient
import com.jervis.common.dto.AuthType
import com.jervis.common.dto.bugtracker.BugTrackerAddCommentRpcRequest
import com.jervis.common.dto.bugtracker.BugTrackerCreateIssueRpcRequest
import com.jervis.common.dto.bugtracker.BugTrackerSearchRequest
import com.jervis.common.dto.bugtracker.BugTrackerTransitionRpcRequest
import com.jervis.common.dto.bugtracker.BugTrackerUpdateIssueRpcRequest
import com.jervis.common.dto.wiki.WikiCreatePageRpcRequest
import com.jervis.common.dto.wiki.WikiSearchRequest
import com.jervis.common.dto.wiki.WikiUpdatePageRpcRequest
import com.jervis.common.rpc.withRpcRetry
import com.jervis.common.types.ConnectionId
import com.jervis.configuration.ProviderRegistry
import com.jervis.dto.connection.ProviderEnum
import com.jervis.entity.SystemConfigDocument
import com.jervis.entity.connection.ConnectionDocument
import com.jervis.integration.bugtracker.BugTrackerComment
import com.jervis.integration.bugtracker.BugTrackerIssue
import com.jervis.integration.wiki.WikiPage
import com.jervis.rpc.SystemConfigRpcImpl
import com.jervis.service.connection.ConnectionService
import mu.KotlinLogging
import org.springframework.stereotype.Service

@Service
class BrainWriteServiceImpl(
    private val systemConfigRpcImpl: SystemConfigRpcImpl,
    private val connectionService: ConnectionService,
    private val bugTrackerClient: IBugTrackerClient,
    private val wikiClient: IWikiClient,
    private val providerRegistry: ProviderRegistry,
) : BrainWriteService {
    private val logger = KotlinLogging.logger {}

    override suspend fun createIssue(
        summary: String,
        description: String?,
        issueType: String,
        priority: String?,
        labels: List<String>,
        epicKey: String?,
    ): BugTrackerIssue {
        val (conn, config) = resolveBugTracker()
        val projectKey = config.brainBugtrackerProjectKey
            ?: throw IllegalStateException("Brain bugtracker project key not configured")

        // Use configured issue type if available, otherwise fall back to parameter default
        val effectiveIssueType = config.brainBugtrackerIssueType ?: issueType

        val response = withRpcRetry(
            name = "BrainCreateIssue",
            reconnect = { providerRegistry.reconnect(ProviderEnum.ATLASSIAN) },
        ) {
            bugTrackerClient.createIssue(
                BugTrackerCreateIssueRpcRequest(
                    baseUrl = conn.baseUrl,
                    authType = AuthType.valueOf(conn.authType.name),
                    basicUsername = conn.username,
                    basicPassword = conn.password,
                    bearerToken = conn.bearerToken,
                    cloudId = conn.cloudId,
                    projectKey = projectKey,
                    summary = summary,
                    description = description,
                    issueType = effectiveIssueType,
                    priority = priority,
                    labels = labels,
                    epicKey = epicKey,
                ),
            )
        }.issue

        logger.info { "Brain issue created: ${response.key} - ${response.title}" }
        return response.toBugTrackerIssue()
    }

    override suspend fun updateIssue(
        issueKey: String,
        summary: String?,
        description: String?,
        assignee: String?,
        priority: String?,
        labels: List<String>?,
    ): BugTrackerIssue {
        val (conn, _) = resolveBugTracker()

        val response = withRpcRetry(
            name = "BrainUpdateIssue",
            reconnect = { providerRegistry.reconnect(ProviderEnum.ATLASSIAN) },
        ) {
            bugTrackerClient.updateIssue(
                BugTrackerUpdateIssueRpcRequest(
                    baseUrl = conn.baseUrl,
                    authType = AuthType.valueOf(conn.authType.name),
                    basicUsername = conn.username,
                    basicPassword = conn.password,
                    bearerToken = conn.bearerToken,
                    cloudId = conn.cloudId,
                    issueKey = issueKey,
                    summary = summary,
                    description = description,
                    assignee = assignee,
                    priority = priority,
                    labels = labels,
                ),
            )
        }.issue

        logger.info { "Brain issue updated: ${response.key}" }
        return response.toBugTrackerIssue()
    }

    override suspend fun addComment(
        issueKey: String,
        comment: String,
    ): BugTrackerComment {
        val (conn, _) = resolveBugTracker()

        val response = withRpcRetry(
            name = "BrainAddComment",
            reconnect = { providerRegistry.reconnect(ProviderEnum.ATLASSIAN) },
        ) {
            bugTrackerClient.addComment(
                BugTrackerAddCommentRpcRequest(
                    baseUrl = conn.baseUrl,
                    authType = AuthType.valueOf(conn.authType.name),
                    basicUsername = conn.username,
                    basicPassword = conn.password,
                    bearerToken = conn.bearerToken,
                    cloudId = conn.cloudId,
                    issueKey = issueKey,
                    body = comment,
                ),
            )
        }

        return BugTrackerComment(
            id = response.id,
            author = response.author ?: "Jervis",
            body = response.body,
            created = response.created,
        )
    }

    override suspend fun transitionIssue(
        issueKey: String,
        transitionName: String,
    ) {
        val (conn, _) = resolveBugTracker()

        withRpcRetry(
            name = "BrainTransitionIssue",
            reconnect = { providerRegistry.reconnect(ProviderEnum.ATLASSIAN) },
        ) {
            bugTrackerClient.transitionIssue(
                BugTrackerTransitionRpcRequest(
                    baseUrl = conn.baseUrl,
                    authType = AuthType.valueOf(conn.authType.name),
                    basicUsername = conn.username,
                    basicPassword = conn.password,
                    bearerToken = conn.bearerToken,
                    cloudId = conn.cloudId,
                    issueKey = issueKey,
                    transitionName = transitionName,
                ),
            )
        }

        logger.info { "Brain issue $issueKey transitioned to $transitionName" }
    }

    override suspend fun searchIssues(
        jql: String,
        maxResults: Int,
    ): List<BugTrackerIssue> {
        val (conn, config) = resolveBugTracker()
        val projectKey = config.brainBugtrackerProjectKey
            ?: throw IllegalStateException("Brain bugtracker project key not configured")

        // Scope JQL to brain project
        val scopedJql = "project = \"$projectKey\" AND ($jql)"

        val response = withRpcRetry(
            name = "BrainSearchIssues",
            reconnect = { providerRegistry.reconnect(ProviderEnum.ATLASSIAN) },
        ) {
            bugTrackerClient.searchIssues(
                BugTrackerSearchRequest(
                    baseUrl = conn.baseUrl,
                    authType = AuthType.valueOf(conn.authType.name),
                    basicUsername = conn.username,
                    basicPassword = conn.password,
                    bearerToken = conn.bearerToken,
                    cloudId = conn.cloudId,
                    query = scopedJql,
                    maxResults = maxResults,
                ),
            )
        }

        return response.issues.map { it.toBugTrackerIssue() }
    }

    override suspend fun createPage(
        title: String,
        content: String,
        parentPageId: String?,
    ): WikiPage {
        val (conn, config) = resolveWiki()
        val spaceKey = config.brainWikiSpaceKey
            ?: throw IllegalStateException("Brain wiki space key not configured")
        val rootPageId = parentPageId ?: config.brainWikiRootPageId

        val response = withRpcRetry(
            name = "BrainCreatePage",
            reconnect = { providerRegistry.reconnect(ProviderEnum.ATLASSIAN) },
        ) {
            wikiClient.createPage(
                WikiCreatePageRpcRequest(
                    baseUrl = conn.baseUrl,
                    authType = AuthType.valueOf(conn.authType.name),
                    basicUsername = conn.username,
                    basicPassword = conn.password,
                    bearerToken = conn.bearerToken,
                    cloudId = conn.cloudId,
                    spaceKey = spaceKey,
                    title = title,
                    content = content,
                    parentPageId = rootPageId,
                ),
            )
        }.page

        logger.info { "Brain page created: ${response.id} - ${response.title}" }
        return WikiPage(
            id = response.id,
            title = response.title,
            content = response.content ?: content,
            spaceKey = response.spaceKey ?: spaceKey,
            created = response.created,
            updated = response.updated,
            version = 1,
            parentId = response.parentId ?: rootPageId,
        )
    }

    override suspend fun updatePage(
        pageId: String,
        title: String,
        content: String,
        version: Int,
    ): WikiPage {
        val (conn, config) = resolveWiki()

        val response = withRpcRetry(
            name = "BrainUpdatePage",
            reconnect = { providerRegistry.reconnect(ProviderEnum.ATLASSIAN) },
        ) {
            wikiClient.updatePage(
                WikiUpdatePageRpcRequest(
                    baseUrl = conn.baseUrl,
                    authType = AuthType.valueOf(conn.authType.name),
                    basicUsername = conn.username,
                    basicPassword = conn.password,
                    bearerToken = conn.bearerToken,
                    cloudId = conn.cloudId,
                    pageId = pageId,
                    title = title,
                    content = content,
                    version = version,
                ),
            )
        }.page

        logger.info { "Brain page updated: ${response.id}" }
        return WikiPage(
            id = response.id,
            title = response.title,
            content = response.content ?: content,
            spaceKey = response.spaceKey ?: config.brainWikiSpaceKey ?: "",
            created = response.created,
            updated = response.updated,
            version = version,
            parentId = response.parentId,
        )
    }

    override suspend fun searchPages(
        query: String,
        maxResults: Int,
    ): List<WikiPage> {
        val (conn, config) = resolveWiki()
        val spaceKey = config.brainWikiSpaceKey

        val response = withRpcRetry(
            name = "BrainSearchPages",
            reconnect = { providerRegistry.reconnect(ProviderEnum.ATLASSIAN) },
        ) {
            wikiClient.searchPages(
                WikiSearchRequest(
                    baseUrl = conn.baseUrl,
                    authType = AuthType.valueOf(conn.authType.name),
                    basicUsername = conn.username,
                    basicPassword = conn.password,
                    bearerToken = conn.bearerToken,
                    cloudId = conn.cloudId,
                    spaceKey = spaceKey,
                    query = query,
                    maxResults = maxResults,
                ),
            )
        }

        return response.pages.map { page ->
            WikiPage(
                id = page.id,
                title = page.title,
                content = page.content ?: "",
                spaceKey = page.spaceKey ?: spaceKey ?: "",
                created = page.created,
                updated = page.updated,
                version = 1,
                parentId = page.parentId,
            )
        }
    }

    override suspend fun isConfigured(): Boolean {
        val config = systemConfigRpcImpl.getDocument()
        return config.brainBugtrackerConnectionId != null &&
            config.brainBugtrackerProjectKey != null &&
            config.brainWikiConnectionId != null &&
            config.brainWikiSpaceKey != null
    }

    // --- Internal helpers ---

    private suspend fun resolveBugTracker(): Pair<ConnectionDocument, SystemConfigDocument> {
        val config = systemConfigRpcImpl.getDocument()
        val connectionId = config.brainBugtrackerConnectionId
            ?: throw IllegalStateException("Brain bugtracker connection not configured")

        val conn = connectionService.findById(ConnectionId(connectionId))
            ?: throw IllegalStateException("Brain bugtracker connection not found: $connectionId")

        return Pair(conn, config)
    }

    private suspend fun resolveWiki(): Pair<ConnectionDocument, SystemConfigDocument> {
        val config = systemConfigRpcImpl.getDocument()
        val connectionId = config.brainWikiConnectionId
            ?: throw IllegalStateException("Brain wiki connection not configured")

        val conn = connectionService.findById(ConnectionId(connectionId))
            ?: throw IllegalStateException("Brain wiki connection not found: $connectionId")

        return Pair(conn, config)
    }

    private fun com.jervis.common.dto.bugtracker.BugTrackerIssueDto.toBugTrackerIssue() =
        BugTrackerIssue(
            key = key,
            summary = title,
            description = description,
            status = status,
            assignee = assignee,
            reporter = reporter ?: "Jervis",
            created = created,
            updated = updated,
            issueType = "Task",
            priority = priority,
            labels = emptyList(),
        )
}
