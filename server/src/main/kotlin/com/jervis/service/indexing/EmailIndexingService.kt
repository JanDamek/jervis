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

                    // TODO: Apache Tika integration for attachment text extraction
                    // Process attachments and extract text content using Apache Tika
                    // For each attachment in serviceMessage.attachments:
                    //   1. Download attachment content from url
                    //   2. Use Apache Tika to extract text (supports PDF, DOCX, XLSX, etc.)
                    //   3. Append extracted text to contentToIndex
                    //   4. For images, add placeholder: "[Image: {filename}]"
                    // Example:
                    // serviceMessage.attachments.forEach { attachment ->
                    //     if (attachment.contentType?.startsWith("image/") == true) {
                    //         appendLine()
                    //         appendLine("[Image: ${attachment.name}]")
                    //         // TODO: Add vision model integration for image description
                    //         // Call vision model API to generate description and append it
                    //     } else {
                    //         val extractedText = tikaService.extractText(attachment.url)
                    //         if (extractedText.isNotBlank()) {
                    //             appendLine()
                    //             appendLine("Attachment: ${attachment.name}")
                    //             append(extractedText)
                    //         }
                    //     }
                    // }
                }

            val chunks = chunkContent(contentToIndex)
            val vectorStoreIds = mutableListOf<String>()

            chunks.forEachIndexed { index, chunk ->
                val embedding = embeddingGateway.callEmbedding(ModelType.EMBEDDING_TEXT, chunk)

                // Store first to get vector ID
                val vectorId =
                    vectorStorage.store(
                        EmbeddingType.EMBEDDING_TEXT,
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
                            vectorStoreIds = emptyList(), // Will be populated on next update if needed
                        ),
                        embedding,
                    )

                vectorStoreIds.add(vectorId)
                logger.debug { "Indexed email chunk $index/${chunks.size} for message ${serviceMessage.id} with vector ID $vectorId" }
            }

            logger.info { "Successfully indexed email ${serviceMessage.id} in ${chunks.size} chunks with IDs: $vectorStoreIds" }

            // TODO: Create background task for email triage
            // backgroundTaskService.createTask(
            //     taskType = TaskType.EMAIL_TRIAGE,
            //     targetRef = TargetRef(type = "email", id = serviceMessage.id, accountId = accountId),
            //     priority = Priority.MEDIUM,
            //     metadata = mapOf(
            //         "subject" to subject,
            //         "from" to from,
            //         "to" to to,
            //         "emailContent" to serviceMessage.content,
            //         "attachmentCount" to serviceMessage.attachments.size.toString()
            //     )
            // )
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

/**
 * Get set of email message IDs that are already indexed for a given account.
 * Queries vector store for all RagDocuments with sourceUri pattern "email://<accountId>/*"
*/
suspend fun getIndexedMessageIds(accountId: String): Set<String> {
logger.debug { "Querying indexed message IDs for account $accountId" }

return try {
val filter = mapOf("sourceUri" to "email://$accountId/")

// Search for all documents matching the account in the email collection
val ragDocs = vectorStorage.searchByFilter(
filter = mapOf(
"ragSourceType" to RagSourceType.EMAIL.name
),
limit = 10000
).toList()

// Filter by sourceUri prefix and extract message IDs
val messageIds = ragDocs
.filter { it.sourceUri?.startsWith("email://$accountId/") == true }
.mapNotNull { doc ->
doc.emailMessageId ?: doc.sourceUri?.substringAfterLast("/")
}
.toSet()

logger.info { "Found ${messageIds.size} indexed messages for account $accountId" }
messageIds
} catch (e: Exception) {
logger.error(e) { "Failed to query indexed message IDs for account $accountId" }
emptySet()
}
}

/**
 * Delete emails from vector store by their message IDs.
 * For each message ID, finds all associated RagDocuments (including chunks) and deletes them.
*/
suspend fun deleteEmailsFromVectorStore(
messageIds: List<String>,
accountId: String,
) {
if (messageIds.isEmpty()) {
logger.debug { "No emails to delete for account $accountId" }
return
}

logger.info { "Deleting ${messageIds.size} emails from vector store for account $accountId" }

messageIds.forEach { messageId ->
try {
val sourceUri = "email://$accountId/$messageId"

// Delete all documents (including chunks) with this sourceUri
val deletedCount = vectorStorage.deleteByFilter(
collectionType = ModelType.EMBEDDING_TEXT,
filter = mapOf("sourceUri" to sourceUri)
)

logger.debug { "Deleted $deletedCount vector entries for message $messageId" }
} catch (e: Exception) {
logger.error(e) { "Failed to delete email $messageId from vector store" }
}
}

logger.info { "Completed deletion of ${messageIds.size} emails from vector store" }
}
}
