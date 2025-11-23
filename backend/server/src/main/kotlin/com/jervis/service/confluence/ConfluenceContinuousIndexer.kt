package com.jervis.service.confluence

import com.jervis.domain.confluence.ConfluencePageContent
import com.jervis.entity.atlassian.AtlassianConnectionDocument
import com.jervis.repository.AtlassianConnectionMongoRepository
import com.jervis.service.confluence.processor.ConfluenceContentProcessor
import com.jervis.service.confluence.processor.ConfluenceTaskCreator
import com.jervis.service.confluence.state.ConfluencePageStateManager
import com.jervis.service.link.LinkIndexingService
import jakarta.annotation.PostConstruct
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import mu.KotlinLogging
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Service

private val logger = KotlinLogging.logger {}

/**
 * Continuous indexer for Confluence pages - similar to EmailContinuousIndexer.
 *
 * Architecture:
 * - Polls NEW pages from ConfluencePageStateManager
 * - Fetches full content from Confluence API
 * - Processes content (parse HTML, extract links, chunk, embed)
 * - Creates pending tasks for GPU analysis
 * - Marks pages as INDEXED or FAILED
 * - Never stops - uses 30s polling delay when queue empty
 *
 * Error Handling:
 * - Communication errors (IMAP/HTTP timeout) → 30s delay before continuing
 * - Logical errors (parsing, validation) → immediate continue
 * - Failed pages marked as FAILED, not retried infinitely
 */
@Service
@Order(10) // Start after WeaviateSchemaInitializer
class ConfluenceContinuousIndexer(
    private val connectionRepository: AtlassianConnectionMongoRepository,
    private val confluenceApiClient: ConfluenceApiClient,
    private val stateManager: ConfluencePageStateManager,
    private val contentProcessor: ConfluenceContentProcessor,
    private val taskCreator: ConfluenceTaskCreator,
    private val flowProps: com.jervis.configuration.properties.IndexingFlowProperties,
    private val linkIndexingService: LinkIndexingService,
    private val connectionService: com.jervis.service.atlassian.AtlassianConnectionService,
    private val linkIndexingQueue: com.jervis.service.indexing.LinkIndexingQueue,
) : com.jervis.service.indexing.AbstractContinuousIndexer<AtlassianConnectionDocument, com.jervis.entity.ConfluencePageDocument>() {
    override val indexerName: String = "ConfluenceContinuousIndexer"
    override val bufferSize: Int get() = flowProps.bufferSize

    private val supervisor = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + supervisor)

    @PostConstruct
    fun start() {
        logger.info { "Starting $indexerName (single instance for all connections)..." }
        scope.launch {
            runCatching { startContinuousIndexing() }
                .onFailure { e -> logger.error(e) { "Continuous indexer crashed" } }
        }
    }

    override fun newItemsFlow() = stateManager.continuousNewPagesAllAccounts()

    override suspend fun getAccountForItem(item: com.jervis.entity.ConfluencePageDocument): AtlassianConnectionDocument? =
        connectionRepository.findById(item.accountId)

    override fun itemLogLabel(item: com.jervis.entity.ConfluencePageDocument) = "${item.title} (${item.pageId}) v${item.lastKnownVersion}"

    override suspend fun fetchContentIO(
        connection: AtlassianConnectionDocument,
        item: com.jervis.entity.ConfluencePageDocument,
    ): Any? =
        try {
            confluenceApiClient.getPageContent(connection, item.pageId)
        } catch (e: ConfluenceAuthException) {
            // Mark connection as invalid to avoid further attempts
            runCatching { connectionService.markAuthInvalid(connection, item.clientId, e.message) }
            null
        }

    override suspend fun processAndIndex(
        connection: AtlassianConnectionDocument,
        item: com.jervis.entity.ConfluencePageDocument,
        content: Any,
    ): IndexingResult {
        val pageContent = content as ConfluencePageContent
        val html =
            pageContent.bodyHtml ?: return IndexingResult(
                success = false,
            )

        val siteUrl = "https://${connection.tenant}"
        val res = contentProcessor.processAndIndexPage(item, html, siteUrl)
        val success = res.indexedChunks > 0

        // Update page links regardless of success
        stateManager.updatePageLinks(
            page = item,
            internalLinks = res.internalLinks,
            externalLinks = res.externalLinks,
            childPageIds = emptyList(),
        )

        // Internal Confluence links are already tracked in stateManager.updatePageLinks
        // They will be picked up by ConfluencePollingScheduler for recursive indexing
        // Only index EXTERNAL links via link indexer
        if (res.externalLinks.isNotEmpty()) {
            logger.info { "CONFLUENCE_EXTERNAL_LINKS: Indexing ${res.externalLinks.size} external links from page ${item.pageId}" }
            res.externalLinks.forEach { url ->
                runCatching {
                    linkIndexingService.indexUrl(
                        url = url,
                        projectId = item.projectId,
                        clientId = item.clientId,
                    )
                }.onFailure { e ->
                    logger.warn { "Failed to index external link $url from Confluence page ${item.pageId}: ${e.message}" }
                }
            }
        }

        if (res.internalLinks.isNotEmpty()) {
            logger.debug {
                "CONFLUENCE_INTERNAL_LINKS: Found ${res.internalLinks.size} internal Confluence links - " +
                    "they will be indexed by ConfluencePollingScheduler"
            }
        }

        return IndexingResult(
            success = success,
            plainText = res.plainText,
        )
    }

    override fun shouldCreateTask(
        connection: AtlassianConnectionDocument,
        item: com.jervis.entity.ConfluencePageDocument,
        content: Any,
        result: IndexingResult,
    ): Boolean = taskCreator.shouldAnalyzePage(item, result.plainText ?: "")

    override suspend fun createTask(
        connection: AtlassianConnectionDocument,
        item: com.jervis.entity.ConfluencePageDocument,
        content: Any,
        result: IndexingResult,
    ) {
        taskCreator.createPageAnalysisTask(item, result.plainText ?: "")
    }

    override suspend fun markAsIndexed(
        connection: AtlassianConnectionDocument,
        item: com.jervis.entity.ConfluencePageDocument,
    ) {
        stateManager.markAsIndexed(item)
        logger.info { "Successfully indexed Confluence page: ${item.title} (${item.pageId})" }
    }

    override suspend fun markAsFailed(
        connection: AtlassianConnectionDocument?,
        item: com.jervis.entity.ConfluencePageDocument,
        reason: String,
    ) {
        stateManager.markAsFailed(item, reason)
    }

}
