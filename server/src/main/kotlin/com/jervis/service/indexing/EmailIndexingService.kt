package com.jervis.service.indexing

import com.jervis.configuration.EmailIndexingProperties
import com.jervis.domain.model.ModelType
import com.jervis.domain.rag.EmbeddingType
import com.jervis.domain.rag.RagDocument
import com.jervis.domain.rag.RagSourceType
import com.jervis.domain.task.PendingTaskSeverity
import com.jervis.domain.task.PendingTaskType
import com.jervis.repository.vector.VectorStorageRepository
import com.jervis.service.document.TikaDocumentProcessor
import com.jervis.service.gateway.EmbeddingGateway
import com.jervis.service.listener.domain.ServiceMessage
import com.jervis.service.task.PendingTaskService
import com.jervis.service.text.TextChunkingService
import dev.langchain4j.data.segment.TextSegment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.toSet
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import org.bson.types.ObjectId
import org.springframework.stereotype.Service
import java.io.ByteArrayInputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

private val logger = KotlinLogging.logger {}

@Service
class EmailIndexingService(
    private val embeddingGateway: EmbeddingGateway,
    private val vectorStorage: VectorStorageRepository,
    private val tikaDocumentProcessor: TikaDocumentProcessor,
    private val pendingTaskService: PendingTaskService,
    private val emailIndexingProperties: EmailIndexingProperties,
    private val textChunkingService: TextChunkingService,
) {
    private val httpClient: HttpClient =
        HttpClient
            .newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build()

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

            val emailContent =
                buildString {
                    appendLine("Subject: $subject")
                    appendLine("From: $from")
                    appendLine("To: $to")
                    appendLine()
                    append(serviceMessage.content)
                }

            val chunks = splitTextIntoChunks(emailContent)
            logger.debug { "Split email ${serviceMessage.id} into ${chunks.size} chunks" }

            chunks.forEachIndexed { index, chunk ->
                val embedding = embeddingGateway.callEmbedding(ModelType.EMBEDDING_TEXT, chunk.text())

                vectorStorage.store(
                    EmbeddingType.EMBEDDING_TEXT,
                    RagDocument(
                        projectId = projectId ?: clientId,
                        clientId = clientId,
                        summary = chunk.text(),
                        ragSourceType = RagSourceType.EMAIL,
                        createdAt = serviceMessage.timestamp,
                        sourceUri = "email://$accountId/${serviceMessage.id}",
                        emailMessageId = serviceMessage.id,
                        chunkId = index,
                        chunkOf = chunks.size,
                    ),
                    embedding,
                )
            }

            logger.info { "Indexed email body ${serviceMessage.id} with ${chunks.size} chunks" }

            if (emailIndexingProperties.attachmentProcessing.enabled) {
                indexEmailAttachments(serviceMessage, clientId, projectId, accountId)
            }

            if (emailIndexingProperties.linkExtraction.enabled) {
                indexEmailLinks(serviceMessage, clientId, projectId, accountId)
            }

            pendingTaskService.createTask(
                taskType = PendingTaskType.EMAIL_PROCESSING,
                severity = PendingTaskSeverity.MEDIUM,
                title = "Analyze email: $subject",
                description = "New email from $from requires analysis and potential action",
                context =
                    mapOf(
                        "emailId" to serviceMessage.id,
                        "accountId" to accountId,
                        "subject" to subject,
                        "from" to from,
                        "to" to to,
                    ),
                errorDetails = null,
                projectId = projectId,
                clientId = clientId,
            )

            logger.info { "Successfully indexed email ${serviceMessage.id} and created pending task" }
        } catch (e: Exception) {
            logger.error(e) { "Failed to index email ${serviceMessage.id}" }
        }
    }

    private fun splitTextIntoChunks(text: String): List<TextSegment> = textChunkingService.splitText(text)

    private suspend fun indexEmailAttachments(
        serviceMessage: ServiceMessage,
        clientId: ObjectId,
        projectId: ObjectId?,
        accountId: String,
    ) = withContext(Dispatchers.IO) {
        val attachments = serviceMessage.metadata["attachments"]?.split(";") ?: emptyList()
        logger.debug { "Processing ${attachments.size} attachments for email ${serviceMessage.id}" }

        attachments.forEachIndexed { attachmentIndex, attachmentInfo ->
            try {
                if (attachmentInfo.isBlank()) return@forEachIndexed

                val parts = attachmentInfo.split("|")
                if (parts.size < 2) return@forEachIndexed

                val fileName = parts[0]
                val attachmentUrl = parts.getOrNull(1) ?: return@forEachIndexed

                logger.debug { "Downloading attachment $fileName from $attachmentUrl" }
                val attachmentBytes = downloadAttachment(attachmentUrl)

                if (attachmentBytes.size > emailIndexingProperties.attachmentProcessing.maxAttachmentSizeMb * 1024 * 1024) {
                    logger.warn { "Attachment $fileName exceeds size limit, skipping" }
                    return@forEachIndexed
                }

                val processingResult =
                    tikaDocumentProcessor.processDocumentStream(
                        inputStream = ByteArrayInputStream(attachmentBytes),
                        fileName = fileName,
                        sourceLocation =
                            TikaDocumentProcessor.SourceLocation(
                                documentPath = "email://$accountId/${serviceMessage.id}/attachment/$attachmentIndex",
                            ),
                    )

                if (!processingResult.success || processingResult.plainText.isBlank()) {
                    logger.debug { "No text extracted from attachment $fileName" }
                    return@forEachIndexed
                }

                val chunks = splitTextIntoChunks(processingResult.plainText)
                logger.debug { "Split attachment $fileName into ${chunks.size} chunks" }

                chunks.forEachIndexed { index, chunk ->
                    val embedding = embeddingGateway.callEmbedding(ModelType.EMBEDDING_TEXT, chunk.text())

                    vectorStorage.store(
                        EmbeddingType.EMBEDDING_TEXT,
                        RagDocument(
                            projectId = projectId ?: clientId,
                            clientId = clientId,
                            summary = chunk.text(),
                            ragSourceType = RagSourceType.EMAIL_ATTACHMENT,
                            createdAt = serviceMessage.timestamp,
                            sourceUri = "email://$accountId/${serviceMessage.id}/attachment/$attachmentIndex",
                            emailMessageId = serviceMessage.id,
                            chunkId = index,
                            chunkOf = chunks.size,
                        ),
                        embedding,
                    )
                }

                logger.info { "Indexed attachment $fileName with ${chunks.size} chunks" }
            } catch (e: Exception) {
                logger.warn(e) { "Failed to process attachment for email ${serviceMessage.id}" }
            }
        }
    }

    private suspend fun indexEmailLinks(
        serviceMessage: ServiceMessage,
        clientId: ObjectId,
        projectId: ObjectId?,
        accountId: String,
    ) = withContext(Dispatchers.IO) {
        val links = extractLinksFromContent(serviceMessage.content)
        val maxLinks = emailIndexingProperties.linkExtraction.maxLinksPerEmail
        val linksToProcess = links.take(maxLinks)

        logger.debug { "Processing ${linksToProcess.size} links from email ${serviceMessage.id}" }

        linksToProcess.forEachIndexed { linkIndex, linkUrl ->
            try {
                logger.debug { "Downloading content from link $linkUrl" }
                val htmlContent = downloadLinkContent(linkUrl)

                val processingResult =
                    tikaDocumentProcessor.processDocumentStream(
                        inputStream = ByteArrayInputStream(htmlContent.toByteArray(Charsets.UTF_8)),
                        fileName = "link-$linkIndex.html",
                        sourceLocation =
                            TikaDocumentProcessor.SourceLocation(
                                documentPath = "email://$accountId/${serviceMessage.id}/link/$linkIndex",
                            ),
                    )

                if (!processingResult.success || processingResult.plainText.isBlank()) {
                    logger.debug { "No text extracted from link $linkUrl" }
                    return@forEachIndexed
                }

                val chunks = splitTextIntoChunks(processingResult.plainText)
                logger.debug { "Split link content from $linkUrl into ${chunks.size} chunks" }

                chunks.forEachIndexed { index, chunk ->
                    val embedding = embeddingGateway.callEmbedding(ModelType.EMBEDDING_TEXT, chunk.text())

                    vectorStorage.store(
                        EmbeddingType.EMBEDDING_TEXT,
                        RagDocument(
                            projectId = projectId ?: clientId,
                            clientId = clientId,
                            summary = chunk.text(),
                            ragSourceType = RagSourceType.EMAIL_LINK_CONTENT,
                            createdAt = serviceMessage.timestamp,
                            sourceUri = linkUrl,
                            emailMessageId = serviceMessage.id,
                            chunkId = index,
                            chunkOf = chunks.size,
                        ),
                        embedding,
                    )
                }

                logger.info { "Indexed link content from $linkUrl with ${chunks.size} chunks" }
            } catch (e: Exception) {
                logger.warn(e) { "Failed to process link $linkUrl for email ${serviceMessage.id}" }
            }
        }
    }

    private fun extractLinksFromContent(content: String): List<String> {
        val urlPattern = Regex("""https?://[^\s<>"{}|\\^`\[\]]+""")
        return urlPattern.findAll(content).map { it.value }.toList()
    }

    private fun downloadAttachment(url: String): ByteArray {
        val request =
            HttpRequest
                .newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray())

        if (response.statusCode() != 200) {
            throw RuntimeException("Failed to download attachment: HTTP ${response.statusCode()}")
        }

        return response.body()
    }

    private fun downloadLinkContent(url: String): String {
        val request =
            HttpRequest
                .newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("User-Agent", "Mozilla/5.0 (compatible; JervisBot/1.0)")
                .GET()
                .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())

        if (response.statusCode() != 200) {
            throw RuntimeException("Failed to download link content: HTTP ${response.statusCode()}")
        }

        return response.body()
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

    suspend fun getIndexedMessageIds(accountId: String): Set<String> {
        logger.debug { "Querying indexed message IDs for account $accountId" }

        return try {
            val ragDocs =
                vectorStorage
                    .searchByFilter(
                        filter =
                            mapOf(
                                "ragSourceType" to RagSourceType.EMAIL.name,
                            ),
                        limit = 10000,
                    )

            val messageIds =
                ragDocs
                    .filter { it.sourceUri?.startsWith("email://$accountId/") == true }
                    .mapNotNull { doc ->
                        doc.emailMessageId ?: doc.sourceUri?.substringAfterLast("/")
                    }.toSet()

            logger.info { "Found ${messageIds.size} indexed messages for account $accountId" }
            messageIds
        } catch (e: Exception) {
            logger.error(e) { "Failed to query indexed message IDs for account $accountId" }
            emptySet()
        }
    }

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

                val deletedCount =
                    vectorStorage.deleteByFilter(
                        collectionType = ModelType.EMBEDDING_TEXT,
                        filter = mapOf("sourceUri" to sourceUri),
                    )

                logger.debug { "Deleted $deletedCount vector entries for message $messageId" }
            } catch (e: Exception) {
                logger.error(e) { "Failed to delete email $messageId from vector store" }
            }
        }

        logger.info { "Completed deletion of ${messageIds.size} emails from vector store" }
    }
}
