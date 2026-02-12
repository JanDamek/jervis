package com.jervis.rpc

import com.jervis.common.types.ClientId
import com.jervis.common.types.ConnectionId
import com.jervis.common.types.ProjectId
import com.jervis.common.types.SourceUrn
import com.jervis.domain.PollingStatusEnum
import com.jervis.dto.indexing.ConnectionIndexingGroupDto
import com.jervis.dto.indexing.IndexingDashboardDto
import com.jervis.dto.indexing.IndexingItemType
import com.jervis.dto.indexing.IndexingQueueItemDto
import com.jervis.dto.indexing.IndexingQueuePageDto
import com.jervis.dto.indexing.KbQueueItemDto
import com.jervis.entity.ClientDocument
import com.jervis.entity.ProjectDocument
import com.jervis.entity.connection.ConnectionDocument
import com.jervis.entity.email.EmailMessageIndexDocument
import com.jervis.integration.bugtracker.internal.entity.BugTrackerIssueIndexDocument
import com.jervis.integration.wiki.internal.entity.WikiPageIndexDocument
import com.jervis.repository.ClientRepository
import com.jervis.repository.ConnectionRepository
import com.jervis.repository.PollingStateRepository
import com.jervis.repository.ProjectRepository
import com.jervis.service.IIndexingQueueService
import com.jervis.service.indexing.git.state.GitCommitDocument
import com.jervis.service.indexing.git.state.GitCommitState
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactor.awaitSingle
import mu.KotlinLogging
import org.bson.types.ObjectId
import org.springframework.data.domain.Sort
import org.springframework.data.mongodb.core.ReactiveMongoTemplate
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeFormatter

@Component
class IndexingQueueRpcImpl(
    private val mongoTemplate: ReactiveMongoTemplate,
    private val connectionRepository: ConnectionRepository,
    private val clientRepository: ClientRepository,
    private val projectRepository: ProjectRepository,
    private val pollingStateRepository: PollingStateRepository,
    private val pollingIntervalRpc: PollingIntervalRpcImpl,
) : IIndexingQueueService {

    private val logger = KotlinLogging.logger {}
    private val isoFormatter = DateTimeFormatter.ISO_INSTANT

    override suspend fun getPendingItems(page: Int, pageSize: Int, search: String): IndexingQueuePageDto {
        return getItems(
            gitStates = listOf(GitCommitState.NEW, GitCommitState.INDEXING),
            pollingStates = listOf(PollingStatusEnum.NEW),
            page = page,
            pageSize = pageSize,
            search = search,
        )
    }

    override suspend fun getIndexedItems(page: Int, pageSize: Int, search: String): IndexingQueuePageDto {
        return getItems(
            gitStates = listOf(GitCommitState.INDEXED),
            pollingStates = listOf(PollingStatusEnum.INDEXED),
            page = page,
            pageSize = pageSize,
            search = search,
        )
    }

    override suspend fun getIndexingDashboard(search: String, kbPageSize: Int): IndexingDashboardDto {
        val now = Instant.now()

        // Build name caches
        val connections = connectionRepository.findAll().toList()
        val connectionMap = connections.associateBy { it.id }
        val clients = clientRepository.findAll().toList()
        val clientMap = clients.associateBy { it.id }
        val clientMapByObjectId = clients.associateBy { it.id.value }
        val projects = projectRepository.findAll().toList()
        val projectMap = projects.associateBy { it.id }
        val projectMapByObjectId = projects.associateBy { it.id.value }

        // Load polling states and interval settings
        val pollingStates = pollingStateRepository.findAll().toList()
        val pollingStatesByConnection = pollingStates.groupBy { it.connectionId }
        val intervalSettings = pollingIntervalRpc.getSettings()

        val query = search.trim().lowercase()

        // ── Collect pending items (NEW/INDEXING) ──
        val pendingItems = mutableListOf<IndexingQueueItemDto>()
        collectGitItems(pendingItems, listOf(GitCommitState.NEW, GitCommitState.INDEXING), clientMapByObjectId, projectMapByObjectId)
        collectEmailItems(pendingItems, listOf(PollingStatusEnum.NEW), connectionMap, clientMap, projectMap)
        collectBugTrackerItems(pendingItems, listOf(PollingStatusEnum.NEW), connectionMap, clientMap, projectMap)
        collectWikiItems(pendingItems, listOf(PollingStatusEnum.NEW), connectionMap, clientMap, projectMap)

        // Apply search filter
        val filteredPending = filterItems(pendingItems, query)

        // Group by connectionId
        val grouped = filteredPending.groupBy { it.connectionId }

        // Build connection groups
        val connectionGroups = grouped.map { (connId, items) ->
            val sortedItems = items.sortedByDescending { it.createdAt ?: "" }
            val conn = if (connId.isNotEmpty()) connectionMap.values.find { it.id.value.toHexString() == connId } else null

            // Compute next check time from polling states
            val connPollingStates = if (connId.isNotEmpty()) {
                try {
                    val connectionId = ConnectionId(ObjectId(connId))
                    pollingStatesByConnection[connectionId] ?: emptyList()
                } catch (_: Exception) {
                    emptyList()
                }
            } else {
                emptyList()
            }

            // Find the primary capability interval
            val capabilities = conn?.availableCapabilities?.map { it.name } ?: if (connId.isEmpty()) listOf("REPOSITORY") else emptyList()
            val primaryCapability = conn?.availableCapabilities?.firstOrNull()
            val intervalMinutes = primaryCapability?.let { intervalSettings.intervals[it] } ?: 30

            // Compute nextCheckAt: earliest (lastUpdated + interval) across all polling states for this connection
            val lastPolledAt = connPollingStates.maxByOrNull { it.lastUpdated }?.lastUpdated
            val nextCheckAt = lastPolledAt?.plus(Duration.ofMinutes(intervalMinutes.toLong()))

            ConnectionIndexingGroupDto(
                connectionId = connId,
                connectionName = items.first().connectionName,
                provider = conn?.provider?.name ?: if (connId.isEmpty()) "GIT" else "UNKNOWN",
                capabilities = capabilities,
                lastPolledAt = lastPolledAt?.formatIso(),
                nextCheckAt = nextCheckAt?.formatIso(),
                intervalMinutes = intervalMinutes,
                items = sortedItems.take(50),
                totalItemCount = sortedItems.size,
            )
        }.sortedByDescending { it.totalItemCount }

        // ── Collect KB items (INDEXED) ──
        val kbItems = mutableListOf<KbQueueItemDto>()
        collectKbGitItems(kbItems, clientMapByObjectId, projectMapByObjectId)
        collectKbEmailItems(kbItems, connectionMap, clientMap, now)
        collectKbBugTrackerItems(kbItems, connectionMap, clientMap, now)
        collectKbWikiItems(kbItems, connectionMap, clientMap, now)

        // Apply search filter to KB items
        val filteredKb = if (query.isBlank()) {
            kbItems
        } else {
            kbItems.filter { item ->
                item.title.lowercase().contains(query) ||
                    item.connectionName.lowercase().contains(query) ||
                    item.clientName.lowercase().contains(query)
            }
        }

        val sortedKb = filteredKb.sortedByDescending { it.indexedAt ?: "" }
        val safeKbPageSize = kbPageSize.coerceIn(1, 100)

        return IndexingDashboardDto(
            connectionGroups = connectionGroups,
            kbQueue = sortedKb.take(safeKbPageSize),
            kbQueueTotalCount = sortedKb.size.toLong(),
        )
    }

    // ── Pending item collectors (with sourceUrn) ──

    private suspend fun collectGitItems(
        items: MutableList<IndexingQueueItemDto>,
        states: List<GitCommitState>,
        clientMapByObjectId: Map<ObjectId, ClientDocument>,
        projectMapByObjectId: Map<ObjectId, ProjectDocument>,
    ) {
        val gitQuery = Query(Criteria.where("state").`in`(states.map { it.name }))
            .with(Sort.by(Sort.Direction.DESC, "commitDate"))
        val gitDocs = mongoTemplate.find(gitQuery, GitCommitDocument::class.java, "git_commits")
            .collectList().awaitSingle()

        for (doc in gitDocs) {
            val clientName = doc.clientId.let { clientMapByObjectId[it]?.name } ?: doc.clientId.toHexString()
            val projectName = doc.projectId?.let { projectMapByObjectId[it]?.name }
            val sourceUrn = doc.projectId?.let {
                SourceUrn.git(ProjectId(it), doc.commitHash).value
            }

            items += IndexingQueueItemDto(
                id = doc.id.toHexString(),
                type = IndexingItemType.GIT_COMMIT,
                connectionName = "Git",
                connectionId = "",
                clientName = clientName,
                projectName = projectName,
                title = doc.message?.take(120) ?: doc.commitHash.take(8),
                createdAt = doc.commitDate?.formatIso(),
                state = doc.state.name,
                errorMessage = null,
                sourceUrn = sourceUrn,
            )
        }
    }

    private suspend fun collectEmailItems(
        items: MutableList<IndexingQueueItemDto>,
        states: List<PollingStatusEnum>,
        connectionMap: Map<ConnectionId, ConnectionDocument>,
        clientMap: Map<ClientId, ClientDocument>,
        projectMap: Map<ProjectId, ProjectDocument>,
    ) {
        val emailQuery = Query(Criteria.where("state").`in`(states.map { it.name }))
            .with(Sort.by(Sort.Direction.DESC, "receivedDate"))
        val emailDocs = mongoTemplate.find(emailQuery, EmailMessageIndexDocument::class.java, "email_message_index")
            .collectList().awaitSingle()

        for (doc in emailDocs) {
            val connName = connectionMap[doc.connectionId]?.name ?: "Email"
            val clientName = clientMap[doc.clientId]?.name ?: doc.clientId.value.toHexString()
            val projectName = doc.projectId?.let { projectMap[it]?.name }
            val sourceUrn = doc.messageId?.let {
                SourceUrn.email(doc.connectionId, it, doc.subject ?: "").value
            }

            items += IndexingQueueItemDto(
                id = doc.id.toHexString(),
                type = IndexingItemType.EMAIL,
                connectionName = connName,
                connectionId = doc.connectionId.value.toHexString(),
                clientName = clientName,
                projectName = projectName,
                title = doc.subject ?: "(bez předmětu)",
                createdAt = doc.receivedDate.formatIso(),
                state = doc.state.name,
                errorMessage = doc.indexingError,
                sourceUrn = sourceUrn,
            )
        }
    }

    private suspend fun collectBugTrackerItems(
        items: MutableList<IndexingQueueItemDto>,
        states: List<PollingStatusEnum>,
        connectionMap: Map<ConnectionId, ConnectionDocument>,
        clientMap: Map<ClientId, ClientDocument>,
        projectMap: Map<ProjectId, ProjectDocument>,
    ) {
        val btQuery = Query(Criteria.where("status").`in`(states.map { it.name }))
            .with(Sort.by(Sort.Direction.DESC, "bugtrackerUpdatedAt"))
        val btDocs = mongoTemplate.find(btQuery, BugTrackerIssueIndexDocument::class.java, "bugtracker_issues")
            .collectList().awaitSingle()

        for (doc in btDocs) {
            val connName = connectionMap[doc.connectionId]?.name ?: "Bugtracker"
            val clientName = clientMap[doc.clientId]?.name ?: doc.clientId.value.toHexString()
            val projectName = doc.projectId?.let { projectMap[it]?.name }
            val sourceUrn = SourceUrn.jira(doc.connectionId.value, doc.issueKey).value

            items += IndexingQueueItemDto(
                id = doc.id.toHexString(),
                type = IndexingItemType.BUGTRACKER_ISSUE,
                connectionName = connName,
                connectionId = doc.connectionId.value.toHexString(),
                clientName = clientName,
                projectName = projectName,
                title = listOfNotNull(doc.issueKey, doc.summary).joinToString(" – "),
                createdAt = doc.bugtrackerUpdatedAt.formatIso(),
                state = doc.status.name,
                errorMessage = doc.indexingError,
                sourceUrn = sourceUrn,
            )
        }
    }

    private suspend fun collectWikiItems(
        items: MutableList<IndexingQueueItemDto>,
        states: List<PollingStatusEnum>,
        connectionMap: Map<ConnectionId, ConnectionDocument>,
        clientMap: Map<ClientId, ClientDocument>,
        projectMap: Map<ProjectId, ProjectDocument>,
    ) {
        val wikiQuery = Query(Criteria.where("status").`in`(states.map { it.name }))
            .with(Sort.by(Sort.Direction.DESC, "wikiUpdatedAt"))
        val wikiDocs = mongoTemplate.find(wikiQuery, WikiPageIndexDocument::class.java, "wiki_pages")
            .collectList().awaitSingle()

        for (doc in wikiDocs) {
            val connName = connectionMap[doc.connectionDocumentId]?.name ?: "Wiki"
            val clientName = clientMap[doc.clientId]?.name ?: doc.clientId.value.toHexString()
            val projectName = doc.projectId?.let { projectMap[it]?.name }
            val sourceUrn = SourceUrn.confluence(doc.connectionDocumentId.value, doc.pageId).value

            items += IndexingQueueItemDto(
                id = doc.id.toHexString(),
                type = IndexingItemType.WIKI_PAGE,
                connectionName = connName,
                connectionId = doc.connectionDocumentId.value.toHexString(),
                clientName = clientName,
                projectName = projectName,
                title = doc.title,
                createdAt = doc.wikiUpdatedAt.formatIso(),
                state = doc.status.name,
                errorMessage = doc.indexingError,
                sourceUrn = sourceUrn,
            )
        }
    }

    // ── KB item collectors (INDEXED state) ──

    private suspend fun collectKbGitItems(
        items: MutableList<KbQueueItemDto>,
        clientMapByObjectId: Map<ObjectId, ClientDocument>,
        projectMapByObjectId: Map<ObjectId, ProjectDocument>,
    ) {
        val gitQuery = Query(Criteria.where("state").`is`(GitCommitState.INDEXED.name))
            .with(Sort.by(Sort.Direction.DESC, "commitDate"))
        val gitDocs = mongoTemplate.find(gitQuery, GitCommitDocument::class.java, "git_commits")
            .collectList().awaitSingle()

        for (doc in gitDocs) {
            val clientName = doc.clientId.let { clientMapByObjectId[it]?.name } ?: doc.clientId.toHexString()
            val sourceUrn = doc.projectId?.let { SourceUrn.git(ProjectId(it), doc.commitHash).value }

            items += KbQueueItemDto(
                id = doc.id.toHexString(),
                type = IndexingItemType.GIT_COMMIT,
                title = doc.message?.take(120) ?: doc.commitHash.take(8),
                connectionName = "Git",
                clientName = clientName,
                sourceUrn = sourceUrn,
                indexedAt = doc.commitDate?.formatIso(),
                waitingDurationMinutes = null,
            )
        }
    }

    private suspend fun collectKbEmailItems(
        items: MutableList<KbQueueItemDto>,
        connectionMap: Map<ConnectionId, ConnectionDocument>,
        clientMap: Map<ClientId, ClientDocument>,
        now: Instant,
    ) {
        val emailQuery = Query(Criteria.where("state").`is`(PollingStatusEnum.INDEXED.name))
            .with(Sort.by(Sort.Direction.DESC, "receivedDate"))
        val emailDocs = mongoTemplate.find(emailQuery, EmailMessageIndexDocument::class.java, "email_message_index")
            .collectList().awaitSingle()

        for (doc in emailDocs) {
            val connName = connectionMap[doc.connectionId]?.name ?: "Email"
            val clientName = clientMap[doc.clientId]?.name ?: doc.clientId.value.toHexString()
            val sourceUrn = doc.messageId?.let {
                SourceUrn.email(doc.connectionId, it, doc.subject ?: "").value
            }

            items += KbQueueItemDto(
                id = doc.id.toHexString(),
                type = IndexingItemType.EMAIL,
                title = doc.subject ?: "(bez předmětu)",
                connectionName = connName,
                clientName = clientName,
                sourceUrn = sourceUrn,
                indexedAt = doc.receivedDate.formatIso(),
                waitingDurationMinutes = Duration.between(doc.receivedDate, now).toMinutes(),
            )
        }
    }

    private suspend fun collectKbBugTrackerItems(
        items: MutableList<KbQueueItemDto>,
        connectionMap: Map<ConnectionId, ConnectionDocument>,
        clientMap: Map<ClientId, ClientDocument>,
        now: Instant,
    ) {
        val btQuery = Query(Criteria.where("status").`is`(PollingStatusEnum.INDEXED.name))
            .with(Sort.by(Sort.Direction.DESC, "bugtrackerUpdatedAt"))
        val btDocs = mongoTemplate.find(btQuery, BugTrackerIssueIndexDocument::class.java, "bugtracker_issues")
            .collectList().awaitSingle()

        for (doc in btDocs) {
            val connName = connectionMap[doc.connectionId]?.name ?: "Bugtracker"
            val clientName = clientMap[doc.clientId]?.name ?: doc.clientId.value.toHexString()
            val sourceUrn = SourceUrn.jira(doc.connectionId.value, doc.issueKey).value

            items += KbQueueItemDto(
                id = doc.id.toHexString(),
                type = IndexingItemType.BUGTRACKER_ISSUE,
                title = listOfNotNull(doc.issueKey, doc.summary).joinToString(" – "),
                connectionName = connName,
                clientName = clientName,
                sourceUrn = sourceUrn,
                indexedAt = doc.bugtrackerUpdatedAt.formatIso(),
                waitingDurationMinutes = Duration.between(doc.bugtrackerUpdatedAt, now).toMinutes(),
            )
        }
    }

    private suspend fun collectKbWikiItems(
        items: MutableList<KbQueueItemDto>,
        connectionMap: Map<ConnectionId, ConnectionDocument>,
        clientMap: Map<ClientId, ClientDocument>,
        now: Instant,
    ) {
        val wikiQuery = Query(Criteria.where("status").`is`(PollingStatusEnum.INDEXED.name))
            .with(Sort.by(Sort.Direction.DESC, "wikiUpdatedAt"))
        val wikiDocs = mongoTemplate.find(wikiQuery, WikiPageIndexDocument::class.java, "wiki_pages")
            .collectList().awaitSingle()

        for (doc in wikiDocs) {
            val connName = connectionMap[doc.connectionDocumentId]?.name ?: "Wiki"
            val clientName = clientMap[doc.clientId]?.name ?: doc.clientId.value.toHexString()
            val sourceUrn = SourceUrn.confluence(doc.connectionDocumentId.value, doc.pageId).value

            items += KbQueueItemDto(
                id = doc.id.toHexString(),
                type = IndexingItemType.WIKI_PAGE,
                title = doc.title,
                connectionName = connName,
                clientName = clientName,
                sourceUrn = sourceUrn,
                indexedAt = doc.wikiUpdatedAt.formatIso(),
                waitingDurationMinutes = Duration.between(doc.wikiUpdatedAt, now).toMinutes(),
            )
        }
    }

    // ── Legacy flat-list implementation (kept for backward compatibility) ──

    private suspend fun getItems(
        gitStates: List<GitCommitState>,
        pollingStates: List<PollingStatusEnum>,
        page: Int,
        pageSize: Int,
        search: String,
    ): IndexingQueuePageDto {
        val connections = connectionRepository.findAll().toList()
        val connectionMap = connections.associateBy { it.id }
        val clients = clientRepository.findAll().toList()
        val clientMap = clients.associateBy { it.id }
        val clientMapByObjectId = clients.associateBy { it.id.value }
        val projects = projectRepository.findAll().toList()
        val projectMap = projects.associateBy { it.id }
        val projectMapByObjectId = projects.associateBy { it.id.value }

        val allItems = mutableListOf<IndexingQueueItemDto>()
        collectGitItems(allItems, gitStates, clientMapByObjectId, projectMapByObjectId)
        collectEmailItems(allItems, pollingStates, connectionMap, clientMap, projectMap)
        collectBugTrackerItems(allItems, pollingStates, connectionMap, clientMap, projectMap)
        collectWikiItems(allItems, pollingStates, connectionMap, clientMap, projectMap)

        val filtered = filterItems(allItems, search.trim().lowercase())
        val sorted = filtered.sortedByDescending { it.createdAt ?: "" }

        val safePage = page.coerceAtLeast(0)
        val safePageSize = pageSize.coerceIn(1, 100)
        val start = safePage * safePageSize
        val paged = if (start >= sorted.size) emptyList() else sorted.subList(start, (start + safePageSize).coerceAtMost(sorted.size))

        return IndexingQueuePageDto(
            items = paged,
            totalCount = sorted.size.toLong(),
            page = safePage,
            pageSize = safePageSize,
        )
    }

    private fun filterItems(items: List<IndexingQueueItemDto>, query: String): List<IndexingQueueItemDto> {
        if (query.isBlank()) return items
        return items.filter { item ->
            item.title.lowercase().contains(query) ||
                item.connectionName.lowercase().contains(query) ||
                item.clientName.lowercase().contains(query) ||
                (item.projectName?.lowercase()?.contains(query) == true)
        }
    }

    private fun Instant.formatIso(): String = isoFormatter.format(this)
}
