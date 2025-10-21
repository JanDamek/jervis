package com.jervis.service.indexing

import com.jervis.domain.model.ModelType
import com.jervis.domain.rag.EmbeddingType
import com.jervis.domain.rag.RagDocument
import com.jervis.domain.rag.RagSourceType
import com.jervis.repository.vector.VectorStorageRepository
import com.jervis.service.gateway.EmbeddingGateway
import com.jervis.service.listener.domain.ServiceMessage
import mu.KotlinLogging
import org.bson.types.ObjectId
import org.springframework.stereotype.Service

private val logger = KotlinLogging.logger {}

@Service
class EmailIndexingService(
    private val embeddingGateway: EmbeddingGateway,
    private val vectorStorage: VectorStorageRepository,
) {
    suspend fun indexEmail(
        serviceMessage: ServiceMessage,
        clientId: ObjectId,
        projectId: ObjectId?,
        accountId: String,
    ) {
        logger.info { "Indexing email ${serviceMessage.id} for account $accountId" }

        try {
            val subject = serviceMessage.metadata["subject"] ?: ""
            val from = serviceMessage.metadata["from"] ?: ""
            val to = serviceMessage.metadata["to"] ?: ""

            val contentToIndex =
                buildString {
                    appendLine("Subject: $subject")
                    appendLine("From: $from")
                    appendLine("To: $to")
                    appendLine()
                    append(serviceMessage.content)
                }

            val chunks = chunkContent(contentToIndex)

            chunks.forEachIndexed { index, chunk ->
                val ragDocument =
                    RagDocument(
                        projectId = projectId ?: clientId,
                        clientId = clientId,
                        summary = chunk,
                        ragSourceType = RagSourceType.EMAIL,
                        createdAt = serviceMessage.timestamp,
                        sourceUri = "email://$accountId/${serviceMessage.id}",
                        emailMessageId = serviceMessage.id,
                        chunkId = if (chunks.size > 1) index else null,
                        chunkOf = if (chunks.size > 1) chunks.size else null,
                    )

                val embedding = embeddingGateway.callEmbedding(ModelType.EMBEDDING_TEXT, chunk)
                vectorStorage.store(EmbeddingType.EMBEDDING_TEXT, ragDocument, embedding)

                logger.debug { "Indexed email chunk $index/${chunks.size} for message ${serviceMessage.id}" }
            }

            logger.info { "Successfully indexed email ${serviceMessage.id} in ${chunks.size} chunks" }
        } catch (e: Exception) {
            logger.error(e) { "Failed to index email ${serviceMessage.id}" }
        }
    }

    private fun chunkContent(
        content: String,
        maxChunkSize: Int = 2000,
    ): List<String> {
        if (content.length <= maxChunkSize) {
            return listOf(content)
        }

        val chunks = mutableListOf<String>()
        var startIndex = 0

        while (startIndex < content.length) {
            val endIndex = (startIndex + maxChunkSize).coerceAtMost(content.length)

            var chunkEnd = endIndex
            if (endIndex < content.length) {
                val lastNewline = content.lastIndexOf('\n', endIndex)
                val lastPeriod = content.lastIndexOf('.', endIndex)
                val lastSpace = content.lastIndexOf(' ', endIndex)

                chunkEnd =
                    when {
                        lastNewline > startIndex + (maxChunkSize / 2) -> lastNewline + 1
                        lastPeriod > startIndex + (maxChunkSize / 2) -> lastPeriod + 1
                        lastSpace > startIndex + (maxChunkSize / 2) -> lastSpace + 1
                        else -> endIndex
                    }
            }

            chunks.add(content.substring(startIndex, chunkEnd).trim())
            startIndex = chunkEnd
        }

        return chunks
    }

    suspend fun indexEmails(
        messages: List<ServiceMessage>,
        clientId: ObjectId,
        projectId: ObjectId?,
        accountId: String,
    ) {
        logger.info { "Indexing ${messages.size} emails for account $accountId" }

        messages.forEach { message ->
            indexEmail(message, clientId, projectId, accountId)
        }

        logger.info { "Completed indexing ${messages.size} emails for account $accountId" }
    }
}
