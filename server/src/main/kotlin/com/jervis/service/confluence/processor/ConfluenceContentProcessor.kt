package com.jervis.service.confluence.processor

import com.jervis.common.client.ITikaClient
import com.jervis.domain.confluence.ConfluenceContentResult
import com.jervis.domain.model.ModelTypeEnum
import com.jervis.domain.rag.RagDocument
import com.jervis.domain.rag.RagSourceType
import com.jervis.entity.ConfluencePageDocument
import com.jervis.repository.vector.VectorStorageRepository
import com.jervis.service.gateway.EmbeddingGateway
import com.jervis.service.rag.VectorStoreIndexService
import com.jervis.service.text.TextChunkingService
import mu.KotlinLogging
import org.jsoup.Jsoup
import org.springframework.stereotype.Service
import java.time.Instant

private val logger = KotlinLogging.logger {}

/**
 * Processes Confluence page content for RAG indexing.
 *
 * Responsibilities:
 * - Parse Confluence XHTML storage format
 * - Extract plain text and links
 * - Chunk content using existing ChunkingService pattern
 * - Create TEXT embeddings (not CODE)
 * - Store in vector DB with metadata
 *
 * Link Handling:
 * - Extract all <a> tags from HTML
 * - Classify as internal (same Confluence domain) vs external
 * - Internal links → follow and index recursively
 * - External links → log but don't follow
 * - Relative links → resolve to absolute URLs
 */
@Service
class ConfluenceContentProcessor(
    private val embeddingGateway: EmbeddingGateway,
    private val vectorStorage: VectorStorageRepository,
    private val vectorStoreIndexService: VectorStoreIndexService,
    private val tikaClient: ITikaClient,
    private val textChunkingService: TextChunkingService,
) {
    /**
     * Process page content and index into RAG.
     * Returns number of chunks indexed.
     */
    suspend fun processAndIndexPage(
        page: ConfluencePageDocument,
        htmlContent: String,
        baseUrl: String,
    ): ConfluenceContentResult {
        logger.info { "Processing content for page: ${page.title} (${page.pageId})" }

        try {
            // Parse HTML and extract data
            val parsed = parseHtmlContent(htmlContent, baseUrl)

            // Check if content is empty
            if (parsed.plainText.isBlank()) {
                logger.warn { "Page ${page.pageId} has no text content, skipping indexing" }
                return ConfluenceContentResult(
                    indexedChunks = 0,
                    internalLinks = parsed.internalLinks,
                    externalLinks = parsed.externalLinks,
                    plainText = "",
                )
            }

            // Chunk content via TextChunkingService
            val segments = textChunkingService.splitText(parsed.plainText)
            logger.debug { "Created ${segments.size} chunks for page ${page.pageId}" }

            var indexedChunks = 0

            // Index each chunk
            for ((index, segment) in segments.withIndex()) {
                try {
                    indexChunk(page, segment.text(), index)
                    indexedChunks++
                } catch (e: Exception) {
                    logger.error(e) { "Failed to index chunk $index for page ${page.pageId}" }
                    // Continue with other chunks even if one fails
                }
            }

            logger.info {
                "Indexed page ${page.title}: $indexedChunks chunks, " +
                    "${parsed.internalLinks.size} internal links, ${parsed.externalLinks.size} external links"
            }

            return ConfluenceContentResult(
                indexedChunks = indexedChunks,
                internalLinks = parsed.internalLinks,
                externalLinks = parsed.externalLinks,
                plainText = parsed.plainText,
            )
        } catch (e: Exception) {
            logger.error(e) { "Failed to process page ${page.pageId}" }
            throw e
        }
    }

    /**
     * Parse HTML content and extract plain text + links.
     */
    private suspend fun parseHtmlContent(
        htmlContent: String,
        baseUrl: String,
    ): ParsedContent {
        val doc = Jsoup.parse(htmlContent, baseUrl)

        // Extract all links before cleaning
        val allLinks =
            doc
                .select("a[href]")
                .mapNotNull { element ->
                    val href = element.attr("abs:href") // Resolves relative URLs
                    if (href.isNotBlank()) href else null
                }.distinct()

        // Classify links
        val (internalLinks, externalLinks) = classifyLinks(allLinks, baseUrl)

        // Extract plain text using Tika (authoritative plain text conversion)
        val plainText =
            try {
                val bytes = htmlContent.toByteArray(Charsets.UTF_8)
                val result =
                    tikaClient.process(
                        com.jervis.common.dto.TikaProcessRequest(
                            source =
                                com.jervis.common.dto.TikaProcessRequest.Source.FileBytes(
                                    fileName = "page.html",
                                    dataBase64 =
                                        java.util.Base64
                                            .getEncoder()
                                            .encodeToString(bytes),
                                ),
                            includeMetadata = false,
                        ),
                    )
                if (result.success && result.plainText.isNotBlank()) {
                    result.plainText
                } else {
                    Jsoup
                        .parse(htmlContent, baseUrl)
                        .text()
                        .trim()
                }
            } catch (e: Exception) {
                logger.warn(e) { "Tika failed to extract plain text for Confluence page, fallback to Jsoup.text()" }
                Jsoup.parse(htmlContent, baseUrl).text().trim()
            }

        return ParsedContent(
            plainText = plainText,
            internalLinks = internalLinks,
            externalLinks = externalLinks,
        )
    }

    /**
     * Classify links as internal (same domain) or external.
     */
    private fun classifyLinks(
        links: List<String>,
        baseUrl: String,
    ): Pair<List<String>, List<String>> {
        val baseDomain = extractDomain(baseUrl)

        val internal = mutableListOf<String>()
        val external = mutableListOf<String>()

        for (link in links) {
            val linkDomain = extractDomain(link)

            if (linkDomain == baseDomain) {
                internal.add(link)
            } else {
                external.add(link)
            }
        }

        return internal to external
    }

    /**
     * Extract domain from URL.
     */
    private fun extractDomain(url: String): String? =
        try {
            java.net
                .URI(url)
                .host
                ?.lowercase()
        } catch (e: Exception) {
            null
        }

    /**
     * Index a single chunk into RAG.
     */
    private suspend fun indexChunk(
        page: ConfluencePageDocument,
        chunk: String,
        chunkIndex: Int,
    ) {
        // Generate TEXT embedding
        val embedding = embeddingGateway.callEmbedding(ModelTypeEnum.EMBEDDING_TEXT, chunk)

        // Validate embedding
        if (embedding.isEmpty() || embedding.all { it == 0f }) {
            throw IllegalStateException("Embedding returned empty/zero vector for page ${page.pageId}")
        }

        // Create RAG document
        val ragDocument =
            RagDocument(
                projectId = page.projectId,
                clientId = page.clientId,
                ragSourceType = RagSourceType.CONFLUENCE_PAGE,
                summary = "${page.title} - Part ${chunkIndex + 1}",
                fileName = page.title,
                sourceUri = page.url,
                confluencePageId = page.pageId,
                confluenceSpaceKey = page.spaceKey,
                chunkId = chunkIndex,
                contentType = "confluence-page",
                from = "confluence",
                timestamp = page.lastModifiedAt?.toString() ?: Instant.now().toString(),
            )

        // Store in Qdrant TEXT collection
        val vectorStoreId = vectorStorage.store(ModelTypeEnum.EMBEDDING_TEXT, ragDocument, embedding)

        // Track in MongoDB (only when projectId is available)
        val pid = page.projectId
        if (pid != null) {
            vectorStoreIndexService.trackIndexed(
                projectId = pid,
                clientId = page.clientId,
                branch = "confluence",
                sourceType = RagSourceType.CONFLUENCE_PAGE,
                sourceId = "${page.pageId}:chunk-$chunkIndex",
                vectorStoreId = vectorStoreId,
                vectorStoreName = "confluence-${page.pageId}-$chunkIndex",
                content = chunk,
                filePath = null,
                symbolName = null,
                commitHash = null,
            )
        } else {
            logger.debug { "Skipping vector index tracking for Confluence page ${page.pageId} - projectId is null" }
        }

        logger.debug { "Indexed chunk $chunkIndex for page ${page.pageId}" }
    }

    private data class ParsedContent(
        val plainText: String,
        val internalLinks: List<String>,
        val externalLinks: List<String>,
    )
}
