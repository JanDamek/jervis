package com.jervis.service.link

import com.jervis.configuration.properties.LinkIndexingProperties
import com.jervis.domain.model.ModelTypeEnum
import com.jervis.domain.rag.RagDocument
import com.jervis.domain.rag.RagSourceType
import com.jervis.entity.IndexedLinkDocument
import com.jervis.repository.mongo.IndexedLinkMongoRepository
import com.jervis.service.rag.RagIndexingService
import com.jervis.service.text.TextChunkingService
import com.jervis.service.text.TextNormalizationService
import mu.KotlinLogging
import org.bson.types.ObjectId
import org.springframework.stereotype.Service
import java.time.Instant

private val logger = KotlinLogging.logger {}

@Service
class LinkIndexer(
    private val ragIndexingService: RagIndexingService,
    private val textChunkingService: TextChunkingService,
    private val textNormalizationService: TextNormalizationService,
    private val linkContentService: LinkContentService,
    private val indexedLinkRepo: IndexedLinkMongoRepository,
    private val props: LinkIndexingProperties,
) {
    data class IndexResult(
        val processedChunks: Int,
        val skipped: Boolean,
    )

    /**
     * Index a single link with already fetched content - chunk, embed, and store.
     * If content is null, will fetch it.
     * Called by email indexing and web content tools.
     * Runs asynchronously in coroutine context - multiple links can be processed in parallel.
     */
    suspend fun indexLink(
        url: String,
        projectId: ObjectId?,
        clientId: ObjectId,
        sourceType: RagSourceType,
        createdAt: Instant = Instant.now(),
        emailMessageId: String? = null,
        content: String? = null,
    ): IndexResult {
        // Skip if indexed recently
        val existing = indexedLinkRepo.findByUrl(url)
        val threshold = Instant.now().minus(props.skipIfIndexedWithin)
        if (existing != null && existing.lastIndexedAt.isAfter(threshold)) {
            logger.debug {
                "Skipping link $url (indexed ${existing.lastIndexedAt}) - within skip interval ${props.skipIfIndexedWithin}"
            }
            return IndexResult(processedChunks = 0, skipped = true)
        }

        val plainText =
            content ?: run {
                val link = linkContentService.fetchPlainText(url)
                if (!link.success || link.plainText.isBlank()) {
                    logger.debug { "No text to index for $url (success=${link.success})" }
                    // Mark as indexed even if empty, so we don't retry repeatedly
                    upsertIndexedLink(url)
                    return IndexResult(processedChunks = 0, skipped = true)
                }
                link.plainText
            }

        val normalizedText = textNormalizationService.normalize(plainText)
        val chunks = textChunkingService.splitText(normalizedText)
        logger.debug { "Split link content from $url into ${chunks.size} chunks" }

        chunks.forEachIndexed { chunkIndex, chunk ->
            ragIndexingService.indexDocument(
                RagDocument(
                    projectId = projectId,
                    clientId = clientId,
                    text = chunk.text(),
                    ragSourceType = sourceType,
                    createdAt = createdAt,
                    sourceUri = url,
                    parentRef = emailMessageId,
                    chunkId = chunkIndex,
                    chunkOf = chunks.size,
                ),
                ModelTypeEnum.EMBEDDING_TEXT,
            )
        }

        // Update/insert index record atomically to avoid duplicate key races
        upsertIndexedLink(url)

        logger.info { "Indexed link $url with ${chunks.size} chunks" }
        return IndexResult(processedChunks = chunks.size, skipped = false)
    }

    private suspend fun upsertIndexedLink(url: String) {
        val now = Instant.now()
        val existing = indexedLinkRepo.findByUrl(url)
        if (existing != null) {
            indexedLinkRepo.save(existing.copy(lastIndexedAt = now))
            return
        }
        try {
            indexedLinkRepo.save(
                IndexedLinkDocument(
                    id = ObjectId.get(),
                    url = url,
                    lastIndexedAt = now,
                    contentHash = null,
                ),
            )
        } catch (e: Exception) {
            val message = e.message ?: ""
            // Handle duplicate key race (code 11000) by re-reading and updating
            if (message.contains("E11000") || message.contains("duplicate key", ignoreCase = true)) {
                val found = indexedLinkRepo.findByUrl(url)
                if (found != null) {
                    indexedLinkRepo.save(found.copy(lastIndexedAt = now))
                    return
                }
            }
            throw e
        }
    }
}
