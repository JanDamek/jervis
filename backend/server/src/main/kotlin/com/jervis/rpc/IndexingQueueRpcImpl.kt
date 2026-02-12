package com.jervis.rpc

import com.jervis.domain.PollingStatusEnum
import com.jervis.dto.indexing.IndexingItemType
import com.jervis.dto.indexing.IndexingQueueItemDto
import com.jervis.dto.indexing.IndexingQueuePageDto
import com.jervis.entity.email.EmailMessageIndexDocument
import com.jervis.integration.bugtracker.internal.entity.BugTrackerIssueIndexDocument
import com.jervis.integration.wiki.internal.entity.WikiPageIndexDocument
import com.jervis.repository.ClientRepository
import com.jervis.repository.ConnectionRepository
import com.jervis.repository.ProjectRepository
import com.jervis.service.IIndexingQueueService
import com.jervis.service.indexing.git.state.GitCommitDocument
import com.jervis.service.indexing.git.state.GitCommitState
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactor.awaitSingle
import mu.KotlinLogging
import org.springframework.data.domain.Sort
import org.springframework.data.mongodb.core.ReactiveMongoTemplate
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.format.DateTimeFormatter

@Component
class IndexingQueueRpcImpl(
    private val mongoTemplate: ReactiveMongoTemplate,
    private val connectionRepository: ConnectionRepository,
    private val clientRepository: ClientRepository,
    private val projectRepository: ProjectRepository,
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

    private suspend fun getItems(
        gitStates: List<GitCommitState>,
        pollingStates: List<PollingStatusEnum>,
        page: Int,
        pageSize: Int,
        search: String,
    ): IndexingQueuePageDto {
        // Build name caches for resolution
        val connections = connectionRepository.findAll().toList()
        val connectionMap = connections.associateBy { it.id }

        val clients = clientRepository.findAll().toList()
        val clientMap = clients.associateBy { it.id }
        val clientMapByObjectId = clients.associateBy { it.id.value }

        val projects = projectRepository.findAll().toList()
        val projectMap = projects.associateBy { it.id }
        val projectMapByObjectId = projects.associateBy { it.id.value }

        // Collect all items from all collections
        val allItems = mutableListOf<IndexingQueueItemDto>()

        // 1. Git commits
        val gitQuery = Query(Criteria.where("state").`in`(gitStates.map { it.name }))
            .with(Sort.by(Sort.Direction.DESC, "commitDate"))
        val gitDocs = mongoTemplate.find(gitQuery, GitCommitDocument::class.java, "git_commits")
            .collectList().awaitSingle()

        for (doc in gitDocs) {
            val clientName = doc.clientId.let { clientMapByObjectId[it]?.name } ?: doc.clientId.toHexString()
            val projectName = doc.projectId?.let { projectMapByObjectId[it]?.name }

            allItems += IndexingQueueItemDto(
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
            )
        }

        // 2. Emails
        val emailQuery = Query(Criteria.where("state").`in`(pollingStates.map { it.name }))
            .with(Sort.by(Sort.Direction.DESC, "receivedDate"))
        val emailDocs = mongoTemplate.find(emailQuery, EmailMessageIndexDocument::class.java, "email_message_index")
            .collectList().awaitSingle()

        for (doc in emailDocs) {
            val connName = connectionMap[doc.connectionId]?.name ?: "Email"
            val clientName = clientMap[doc.clientId]?.name ?: doc.clientId.value.toHexString()
            val projectName = doc.projectId?.let { projectMap[it]?.name }

            allItems += IndexingQueueItemDto(
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
            )
        }

        // 3. Bug tracker issues
        val btQuery = Query(Criteria.where("status").`in`(pollingStates.map { it.name }))
            .with(Sort.by(Sort.Direction.DESC, "bugtrackerUpdatedAt"))
        val btDocs = mongoTemplate.find(btQuery, BugTrackerIssueIndexDocument::class.java, "bugtracker_issues")
            .collectList().awaitSingle()

        for (doc in btDocs) {
            val connName = connectionMap[doc.connectionId]?.name ?: "Bugtracker"
            val clientName = clientMap[doc.clientId]?.name ?: doc.clientId.value.toHexString()
            val projectName = doc.projectId?.let { projectMap[it]?.name }

            allItems += IndexingQueueItemDto(
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
            )
        }

        // 4. Wiki pages
        val wikiQuery = Query(Criteria.where("status").`in`(pollingStates.map { it.name }))
            .with(Sort.by(Sort.Direction.DESC, "wikiUpdatedAt"))
        val wikiDocs = mongoTemplate.find(wikiQuery, WikiPageIndexDocument::class.java, "wiki_pages")
            .collectList().awaitSingle()

        for (doc in wikiDocs) {
            val connName = connectionMap[doc.connectionDocumentId]?.name ?: "Wiki"
            val clientName = clientMap[doc.clientId]?.name ?: doc.clientId.value.toHexString()
            val projectName = doc.projectId?.let { projectMap[it]?.name }

            allItems += IndexingQueueItemDto(
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
            )
        }

        // Apply search filter
        val query = search.trim().lowercase()
        val filtered = if (query.isBlank()) {
            allItems
        } else {
            allItems.filter { item ->
                item.title.lowercase().contains(query) ||
                    item.connectionName.lowercase().contains(query) ||
                    item.clientName.lowercase().contains(query) ||
                    (item.projectName?.lowercase()?.contains(query) == true)
            }
        }

        // Sort by createdAt descending (nulls last)
        val sorted = filtered.sortedByDescending { it.createdAt ?: "" }

        // Paginate
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

    private fun Instant.formatIso(): String = isoFormatter.format(this)
}
