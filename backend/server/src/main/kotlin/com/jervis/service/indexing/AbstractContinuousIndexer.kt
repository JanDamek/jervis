package com.jervis.service.indexing

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapConcat
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withContext
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Generic continuous indexer pipeline.
 * Implements: polling -> fetch -> process+index -> task (optional) -> mark state.
 * Fail-fast, no fallback guessing. IO parts forced on Dispatchers.IO.
 *
 * Architecture:
 * - Single indexer instance per type (Email, Confluence, Jira)
 * - Processes items from ALL accounts, ordered by priority (newest first)
 * - Account lookup happens per-item via getAccountForItem()
 *
 * Notes:
 * - No explicit concurrency parameter. Scheduling is controlled by coroutines runtime.
 * - Buffer default is 128 to decouple fast producers from slower consumers without delaying first items.
 *   Subclasses should override bufferSize from a single global property
 *   (jervis.indexing.flow.buffer-size via IndexingFlowProperties) to keep behavior uniform.
 * - Rate limiting is handled at API client level (DomainRateLimiterService)
 */
abstract class AbstractContinuousIndexer<A, I> {
    /** Unified buffer between producer flow and processing. Does NOT delay first items. */
    protected open val bufferSize: Int = 128

    /** Delay applied only for communication errors (HTTP/IMAP/timeouts). */
    protected open val communicationErrorDelayMs: Long = 30_000

    /** For logs / tracing only. */
    protected abstract val indexerName: String

    // --- Domain-specific hooks to be implemented by subclasses ---

    /** Stream of NEW items across ALL accounts. Can be endless or finite. Ordered by priority (newest first). */
    protected abstract fun newItemsFlow(): Flow<I>

    /** Get account/connection for a specific item. Used to lookup account info per-item. */
    protected abstract suspend fun getAccountForItem(item: I): A?

    /** Fetch raw/full content necessary to process the item. Runs on IO dispatcher. Return null to indicate fetch failure. */
    protected abstract suspend fun fetchContentIO(
        account: A,
        item: I,
    ): Any?

    /**
     * Process and index item content into RAG. Return result with success flag and optional plain text/ids.
     * Must be pure service logic, no DTO/Entity leakage beyond current conventions.
     */
    protected abstract suspend fun processAndIndex(
        account: A,
        item: I,
        content: Any,
    ): IndexingResult

    /** Decide if we should create analysis task based on processed content. */
    protected open fun shouldCreateTask(
        account: A,
        item: I,
        content: Any,
        result: IndexingResult,
    ): Boolean = true

    /** Create pending task (if needed) after successful RAG indexing. */
    protected open suspend fun createTask(
        account: A,
        item: I,
        content: Any,
        result: IndexingResult,
    ) {
    }

    /** Mark item as successfully indexed. */
    protected abstract suspend fun markAsIndexed(
        account: A,
        item: I,
    )

    /** Mark item as failed with reason. Account may be null if lookup failed. */
    protected abstract suspend fun markAsFailed(
        account: A?,
        item: I,
        reason: String,
    )

    /** Human-readable context of item for logs (id, subject, title...). */
    protected abstract fun itemLogLabel(item: I): String

    /** Classify if error is communication-related (HTTP/IMAP/timeout...). */
    protected open fun isCommunicationError(t: Throwable): Boolean =
        t.message?.contains("ConnectionDocument", true) == true ||
            t.message?.contains("timeout", true) == true ||
            t.message?.contains("HTTP", true) == true ||
            t.message?.contains("IMAP", true) == true ||
            t is java.net.SocketException ||
            t is java.net.SocketTimeoutException ||
            t is org.springframework.web.reactive.function.client.WebClientRequestException ||
            t is org.springframework.web.reactive.function.client.WebClientResponseException

    data class IndexingResult(
        val success: Boolean,
        val plainText: String? = null,
        val ragDocumentId: String? = null,
    )

    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun startContinuousIndexing() {
        logger.info { "Starting $indexerName" }

        newItemsFlow()
            .buffer(bufferSize)
            .flatMapConcat { item ->
                flow {
                    val label = itemLogLabel(item)

                    // Lookup account for this item
                    val account = getAccountForItem(item)
                    if (account == null) {
                        logger.warn { "[$indexerName] Account not found for $label, skipping" }
                        markAsFailed(null, item, "Account not found")
                        return@flow
                    }

                    logger.info { "[$indexerName] Indexing $label" }

                    val content = withContext(Dispatchers.IO) { fetchContentIO(account, item) }
                    if (content == null) {
                        logger.warn { "[$indexerName] Fetch returned null for $label" }
                        markAsFailed(account, item, "Failed to fetch content")
                        return@flow
                    }

                    val result = processAndIndex(account, item, content)
                    if (!result.success) {
                        logger.error { "[$indexerName] RAG indexing failed for $label" }
                        markAsFailed(account, item, "RAG indexing produced 0 chunks or failed")
                        return@flow
                    }

                    if (shouldCreateTask(account, item, content, result)) {
                        runCatching {
                            createTask(account, item, content, result)
                            logger.info { "[$indexerName] Task created for $label" }
                        }.onFailure { e ->
                            // Do not fail INDEXED state because RAG succeeded
                            logger.error(e) { "[$indexerName] Task creation failed for $label" }
                        }
                    }

                    emit(Pair(account, item))
                }.catch { e ->
                    val label = itemLogLabel(item)
                    logger.error(e) { "[$indexerName] Indexing failed for $label" }

                    if (isCommunicationError(e)) {
                        logger.warn { "[$indexerName] Communication error, pausing ${communicationErrorDelayMs}ms" }
                        delay(communicationErrorDelayMs)
                    }

                    val account = getAccountForItem(item)
                    markAsFailed(account, item, e.message?.take(500) ?: "Unknown error")
                }
            }.onEach { (account, item) ->
                runCatching { markAsIndexed(account, item) }
                    .onFailure { e -> logger.error(e) { "[$indexerName] markAsIndexed failed for ${itemLogLabel(item)}" } }
            }.collect { }
    }
}
