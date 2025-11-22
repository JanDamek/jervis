package com.jervis.service.link

import com.jervis.configuration.properties.LinkIndexingProperties
import com.jervis.entity.IndexedLinkDocument
import com.jervis.rag.DocumentToStore
import com.jervis.rag.EmbeddingType
import com.jervis.rag.KnowledgeService
import com.jervis.rag.KnowledgeType
import com.jervis.repository.IndexedLinkMongoRepository
import mu.KotlinLogging
import org.bson.types.ObjectId
import org.springframework.stereotype.Service
import java.time.Instant

private val logger = KotlinLogging.logger {}

@Service
class LinkIndexer(
    private val knowledgeService: KnowledgeService,
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
        content: String? = null,
    ): IndexResult {
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
                    upsertIndexedLink(url)
                    return IndexResult(processedChunks = 0, skipped = true)
                }
                link.plainText
            }

        val documentToStore =
            DocumentToStore(
                documentId = "link:$url",
                content = plainText,
                clientId = clientId,
                projectId = projectId,
                type = KnowledgeType.DOCUMENT,
                embeddingType = EmbeddingType.TEXT,
                title = url,
                location = url,
            )

        knowledgeService
            .store(com.jervis.rag.StoreRequest(listOf(documentToStore)))

        upsertIndexedLink(url)

        logger.info { "Indexed link $url" }
        return IndexResult(processedChunks = 1, skipped = false)
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
