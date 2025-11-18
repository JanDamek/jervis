package com.jervis.service.confluence

import com.jervis.domain.confluence.ConfluencePageContent
import com.jervis.domain.rag.RagSourceType
import com.jervis.entity.atlassian.AtlassianConnectionDocument
import com.jervis.service.confluence.processor.ConfluenceContentProcessor
import com.jervis.service.confluence.processor.ConfluenceTaskCreator
import com.jervis.service.confluence.state.ConfluencePageStateManager
import com.jervis.service.link.LinkIndexingService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import mu.KotlinLogging
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
class ConfluenceContinuousIndexer(
    private val confluenceApiClient: ConfluenceApiClient,
    private val stateManager: ConfluencePageStateManager,
    private val contentProcessor: ConfluenceContentProcessor,
    private val taskCreator: ConfluenceTaskCreator,
    private val flowProps: com.jervis.configuration.properties.IndexingFlowProperties,
    private val linkIndexingService: LinkIndexingService,
    private val connectionService: com.jervis.service.atlassian.AtlassianConnectionService,
) : com.jervis.service.indexing.AbstractContinuousIndexer<AtlassianConnectionDocument, com.jervis.entity.ConfluencePageDocument>() {
    override val indexerName: String = "ConfluenceContinuousIndexer"
    override val bufferSize: Int get() = flowProps.bufferSize

    override fun newItemsFlow(connection: AtlassianConnectionDocument) = stateManager.continuousNewPages(connection.id)

    override fun itemLogLabel(item: com.jervis.entity.ConfluencePageDocument) = "${item.title} (${item.pageId}) v${item.lastKnownVersion}"

    override suspend fun fetchContentIO(
        connection: AtlassianConnectionDocument,
        item: com.jervis.entity.ConfluencePageDocument,
    ): Any? =
        try {
            confluenceApiClient.getPageContent(connection, item.pageId)
        } catch (e: ConfluenceAuthException) {
            // Mark connection as invalid to avoid further attempts
            runCatching { connectionService.markAuthInvalid(connection, e.message) }
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

        // Index links separately for better discoverability
        val allLinks = res.internalLinks + res.externalLinks
        if (allLinks.isNotEmpty()) {
            logger.info { "CONFLUENCE_LINKS: Indexing ${allLinks.size} links from page ${item.pageId}" }
            allLinks.forEach { url ->
                runCatching {
                    linkIndexingService.indexUrl(
                        url = url,
                        projectId = item.projectId,
                        clientId = item.clientId,
                        sourceType = RagSourceType.CONFLUENCE_LINK_CONTENT,
                        parentRef = item.pageId,
                    )
                }.onFailure { e ->
                    logger.warn { "Failed to index link $url from Confluence page ${item.pageId}: ${e.message}" }
                }
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
        connection: AtlassianConnectionDocument,
        item: com.jervis.entity.ConfluencePageDocument,
        reason: String,
    ) {
        stateManager.markAsFailed(item, reason)
    }

    fun launchContinuousIndexing(
        connection: AtlassianConnectionDocument,
        scope: CoroutineScope,
    ) {
        scope.launch {
            runCatching { startContinuousIndexing(connection) }
                .onFailure { e -> logger.error(e) { "Continuous indexer crashed for Confluence connection ${connection.id}" } }
        }
    }
}
