package com.jervis.service.link

import com.jervis.configuration.LinkIndexingProperties
import com.jervis.domain.model.ModelType
import com.jervis.domain.rag.EmbeddingType
import com.jervis.domain.rag.RagDocument
import com.jervis.domain.rag.RagSourceType
import com.jervis.entity.IndexedLinkDocument
import com.jervis.repository.mongo.IndexedLinkMongoRepository
import com.jervis.repository.vector.VectorStorageRepository
import com.jervis.service.gateway.EmbeddingGateway
import com.jervis.service.text.TextChunkingService
import mu.KotlinLogging
import org.bson.types.ObjectId
import org.springframework.stereotype.Service
import java.time.Instant

private val logger = KotlinLogging.logger {}

@Service
class LinkIndexer(
    private val embeddingGateway: EmbeddingGateway,
    private val vectorStorage: VectorStorageRepository,
    private val textChunkingService: TextChunkingService,
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
                    return IndexResult(processedChunks = 0, skipped = true)
                }
                link.plainText
            }

        val chunks = textChunkingService.splitText(plainText)
        logger.debug { "Split link content from $url into ${chunks.size} chunks" }

        chunks.forEachIndexed { chunkIndex, chunk ->
            val embedding = embeddingGateway.callEmbedding(ModelType.EMBEDDING_TEXT, chunk.text())

            vectorStorage.store(
                EmbeddingType.EMBEDDING_TEXT,
                RagDocument(
                    projectId = projectId,
                    clientId = clientId,
                    summary = chunk.text(),
                    ragSourceType = sourceType,
                    createdAt = createdAt,
                    sourceUri = url,
                    parentRef = emailMessageId,
                    chunkId = chunkIndex,
                    chunkOf = chunks.size,
                ),
                embedding,
            )
        }

        // Update/insert index record
        val updated =
            IndexedLinkDocument(
                id = existing?.id ?: ObjectId.get(),
                url = url,
                lastIndexedAt = Instant.now(),
                contentHash = null,
            )
        indexedLinkRepo.save(updated)

        logger.info { "Indexed link $url with ${chunks.size} chunks" }
        return IndexResult(processedChunks = chunks.size, skipped = false)
    }
}
