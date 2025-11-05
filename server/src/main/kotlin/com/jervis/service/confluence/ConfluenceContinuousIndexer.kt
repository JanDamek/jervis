package com.jervis.service.confluence

import com.jervis.entity.ConfluenceAccountDocument
import com.jervis.service.confluence.processor.ConfluenceContentProcessor
import com.jervis.service.confluence.processor.ConfluenceTaskCreator
import com.jervis.service.confluence.state.ConfluencePageStateManager
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
) : com.jervis.service.indexing.AbstractContinuousIndexer<ConfluenceAccountDocument, com.jervis.entity.ConfluencePageDocument>() {
    override val indexerName: String = "ConfluenceContinuousIndexer"
    override val bufferSize: Int get() = flowProps.bufferSize

    override fun newItemsFlow(account: ConfluenceAccountDocument) = stateManager.continuousNewPages(account.id)

    override fun itemLogLabel(item: com.jervis.entity.ConfluencePageDocument) = "${item.title} (${item.pageId}) v${item.lastKnownVersion}"

    override suspend fun fetchContentIO(
        account: ConfluenceAccountDocument,
        item: com.jervis.entity.ConfluencePageDocument,
    ): Any? = confluenceApiClient.getPageContent(account, item.pageId)

    override suspend fun processAndIndex(
        account: ConfluenceAccountDocument,
        item: com.jervis.entity.ConfluencePageDocument,
        content: Any,
    ): IndexingResult {
        val pageContent = content as com.jervis.domain.confluence.ConfluencePageContent
        val html =
            pageContent.bodyHtml ?: return IndexingResult(
                success = false,
            )

        val res = contentProcessor.processAndIndexPage(item, html, account.siteUrl)
        val success = res.indexedChunks > 0

        // Update page links regardless of success
        stateManager.updatePageLinks(
            page = item,
            internalLinks = res.internalLinks,
            externalLinks = res.externalLinks,
            childPageIds = emptyList(),
        )

        return IndexingResult(
            success = success,
            plainText = res.plainText,
        )
    }

    override fun shouldCreateTask(
        account: ConfluenceAccountDocument,
        item: com.jervis.entity.ConfluencePageDocument,
        content: Any,
        result: IndexingResult,
    ): Boolean = taskCreator.shouldAnalyzePage(item, result.plainText ?: "")

    override suspend fun createTask(
        account: ConfluenceAccountDocument,
        item: com.jervis.entity.ConfluencePageDocument,
        content: Any,
        result: IndexingResult,
    ) {
        taskCreator.createPageAnalysisTask(item, result.plainText ?: "")
    }

    override suspend fun markAsIndexed(
        account: ConfluenceAccountDocument,
        item: com.jervis.entity.ConfluencePageDocument,
    ) {
        stateManager.markAsIndexed(item)
        logger.info { "Successfully indexed Confluence page: ${item.title} (${item.pageId})" }
    }

    override suspend fun markAsFailed(
        account: ConfluenceAccountDocument,
        item: com.jervis.entity.ConfluencePageDocument,
        reason: String,
    ) {
        stateManager.markAsFailed(item, reason)
    }

    fun launchContinuousIndexing(
        account: ConfluenceAccountDocument,
        scope: CoroutineScope,
    ) {
        scope.launch {
            runCatching { startContinuousIndexing(account) }
                .onFailure { e -> logger.error(e) { "Continuous indexer crashed for Confluence account ${account.id}" } }
        }
    }
}
