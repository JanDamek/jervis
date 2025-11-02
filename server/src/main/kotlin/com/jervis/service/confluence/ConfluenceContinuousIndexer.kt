package com.jervis.service.confluence

import com.jervis.entity.ConfluenceAccountDocument
import com.jervis.service.confluence.processor.ConfluenceContentProcessor
import com.jervis.service.confluence.processor.ConfluenceTaskCreator
import com.jervis.service.confluence.state.ConfluencePageStateManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapMerge
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
) {
    /**
     * Starts continuous indexing for an account. Never returns.
     * Should be launched in a separate coroutine scope.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun startContinuousIndexing(account: ConfluenceAccountDocument) {
        logger.info { "Starting continuous indexer for Confluence account ${account.id} (${account.siteName})" }

        stateManager
            .continuousNewPages(account.id)
            .buffer(128) // Back-pressure relief
            .flatMapMerge(concurrency = 3) { pageDoc ->
                // Lower concurrency than email (3 vs 5) to avoid rate limiting
                flow {
                    logger.info {
                        "Indexing Confluence page: ${pageDoc.title} (${pageDoc.pageId}) " +
                            "v${pageDoc.lastKnownVersion} in space ${pageDoc.spaceKey}"
                    }

                    // Fetch full content from Confluence API (runs on IO dispatcher)
                    val pageContent =
                        withContext(Dispatchers.IO) {
                            confluenceApiClient.getPageContent(account, pageDoc.pageId)
                        }

                    if (pageContent == null) {
                        logger.warn {
                            "Could not fetch content for page ${pageDoc.pageId}, marking as FAILED"
                        }
                        stateManager.markAsFailed(pageDoc, "Failed to fetch content from Confluence API")
                        return@flow
                    }

                    val htmlContent = pageContent.bodyHtml

                    if (htmlContent.isNullOrBlank()) {
                        logger.warn {
                            "Page ${pageDoc.pageId} has no content body, marking as FAILED"
                        }
                        stateManager.markAsFailed(pageDoc, "No content body in Confluence response")
                        return@flow
                    }

                    // Process and index content into RAG
                    // This must complete successfully before creating pending task
                    val result = indexPage(account, pageDoc, htmlContent)

                    // Verify RAG indexing succeeded before proceeding
                    if (!result.success) {
                        logger.error {
                            "RAG indexing failed for page ${pageDoc.pageId} - 0 chunks indexed. Marking as FAILED."
                        }
                        stateManager.markAsFailed(
                            pageDoc,
                            "RAG indexing produced 0 chunks - content may be empty or all embeddings failed",
                        )
                        return@flow // Don't emit, don't create task, don't mark as INDEXED
                    }

                    // RAG indexing succeeded - now create analysis task
                    createAnalysisTaskIfNeeded(pageDoc, result.plainText)

                    // Emit for markAsIndexed in onEach
                    emit(pageDoc)
                }.catch { e ->
                    logger.error(e) {
                        "Indexing failed for page ${pageDoc.pageId} (${pageDoc.title})"
                    }

                    // Classify error type
                    val isCommunicationError =
                        e.message?.contains("Connection", ignoreCase = true) == true ||
                            e.message?.contains("timeout", ignoreCase = true) == true ||
                            e.message?.contains("HTTP", ignoreCase = true) == true ||
                            e is java.net.SocketException ||
                            e is java.net.SocketTimeoutException ||
                            e is org.springframework.web.reactive.function.client.WebClientRequestException ||
                            e is org.springframework.web.reactive.function.client.WebClientResponseException

                    if (isCommunicationError) {
                        logger.warn { "Communication error detected, pausing 30s before continuing" }
                        delay(30_000)
                    }

                    // Mark as failed and continue
                    stateManager.markAsFailed(
                        pageDoc,
                        e.message?.take(500) ?: "Unknown error during indexing",
                    )
                }
            }.onEach { pageDoc ->
                stateManager.markAsIndexed(pageDoc)
                logger.info { "Successfully indexed Confluence page: ${pageDoc.title} (${pageDoc.pageId})" }
            }.collect { }
    }

    /**
     * Process and index a single page into RAG.
     * Returns result indicating success and extracted content.
     * Task creation happens AFTER this completes successfully.
     */
    private suspend fun indexPage(
        account: ConfluenceAccountDocument,
        pageDoc: com.jervis.entity.ConfluencePageDocument,
        htmlContent: String,
    ): PageIndexingResult {
        // Process content (parse HTML, chunk, embed into RAG)
        val result = contentProcessor.processAndIndexPage(pageDoc, htmlContent, account.siteUrl)

        // Check if indexing was successful (at least some chunks indexed)
        val success = result.indexedChunks > 0

        logger.info {
            "Processed page ${pageDoc.pageId}: ${result.indexedChunks} chunks, " +
                "${result.internalLinks.size} internal links, ${result.externalLinks.size} external links, " +
                "success=$success"
        }

        if (!success) {
            logger.warn {
                "RAG indexing produced 0 chunks for page ${pageDoc.pageId} - " +
                    "content may be empty or all chunks failed to embed"
            }
        }

        // Update page links in state (regardless of indexing success)
        stateManager.updatePageLinks(
            page = pageDoc,
            internalLinks = result.internalLinks,
            externalLinks = result.externalLinks,
            childPageIds = emptyList(), // Will be populated by polling scheduler
        )

        // Log external links for visibility
        if (result.externalLinks.isNotEmpty()) {
            logger.debug {
                "External links found in ${pageDoc.title}: ${result.externalLinks.take(5).joinToString(", ")}" +
                    if (result.externalLinks.size > 5) " and ${result.externalLinks.size - 5} more" else ""
            }
        }

        return PageIndexingResult(
            success = success,
            indexedChunks = result.indexedChunks,
            plainText = result.plainText,
        )
    }

    /**
     * Create analysis task for GPU if page meets criteria.
     * Called ONLY after successful RAG indexing.
     */
    private suspend fun createAnalysisTaskIfNeeded(
        pageDoc: com.jervis.entity.ConfluencePageDocument,
        plainText: String,
    ) {
        if (!taskCreator.shouldAnalyzePage(pageDoc, plainText)) {
            logger.debug { "Skipped analysis task creation for page: ${pageDoc.title} (doesn't meet criteria)" }
            return
        }

        try {
            taskCreator.createPageAnalysisTask(pageDoc, plainText)
            logger.info {
                "Created CONFLUENCE_PAGE_ANALYSIS task for page: ${pageDoc.title} (${pageDoc.pageId})"
            }
        } catch (e: Exception) {
            logger.error(e) {
                "Failed to create analysis task for page ${pageDoc.pageId}: ${e.message}. " +
                    "RAG indexing completed successfully, but GPU analysis won't run."
            }
            // Don't fail page as INDEXED - it's successfully in RAG, just no GPU analysis
        }
    }

    private data class PageIndexingResult(
        val success: Boolean,
        val indexedChunks: Int,
        val plainText: String,
    )

    /**
     * Helper to launch continuous indexing in a scope.
     * Use this from application startup or account activation.
     */
    fun launchContinuousIndexing(
        account: ConfluenceAccountDocument,
        scope: CoroutineScope,
    ) {
        scope.launch {
            runCatching {
                startContinuousIndexing(account)
            }.onFailure { e ->
                logger.error(e) { "Continuous indexer crashed for Confluence account ${account.id}" }
                // TODO: Implement auto-restart mechanism or alert
            }
        }
    }
}
