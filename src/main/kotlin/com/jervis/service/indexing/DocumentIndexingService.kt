package com.jervis.service.indexing

import com.jervis.configuration.prompts.PromptTypeEnum
import com.jervis.domain.model.ModelType
import com.jervis.domain.rag.RagDocument
import com.jervis.domain.rag.RagSourceType
import com.jervis.entity.mongo.ProjectDocument
import com.jervis.repository.vector.VectorStorageRepository
import com.jervis.service.document.TikaDocumentProcessor
import com.jervis.service.gateway.EmbeddingGateway
import com.jervis.service.gateway.core.LlmGateway
import com.jervis.service.indexing.monitoring.IndexingStepType
import com.jervis.service.rag.RagIndexingStatusService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import mu.KotlinLogging
import org.springframework.stereotype.Service
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Duration
import kotlin.io.path.isRegularFile
import kotlin.io.path.pathString

/**
 * Unified document indexing service that handles all types of documents using TikaDocumentProcessor.
 * Replaces both MeetingTranscriptIndexingService and DocumentationIndexingService.
 * Uses LLM-based atomic sentence splitting for consistent RAG data storage.
 */
@Service
class DocumentIndexingService(
    private val embeddingGateway: EmbeddingGateway,
    private val vectorStorage: VectorStorageRepository,
    private val ragIndexingStatusService: RagIndexingStatusService,
    private val historicalVersioningService: HistoricalVersioningService,
    private val indexingMonitorService: com.jervis.service.indexing.monitoring.IndexingMonitorService,
    private val tikaDocumentProcessor: TikaDocumentProcessor,
    private val llmGateway: LlmGateway,
) {
    private val logger = KotlinLogging.logger {}

    @Serializable
    data class ContentSentenceSplittingResponse(
        val sentences: List<String> = emptyList(),
    )

    data class DocumentIndexingResult(
        val processedDocuments: Int,
        val skippedDocuments: Int,
        val errorDocuments: Int,
    )

    /**
     * Indexes all project documents (meetings, documentation, etc.) using unified approach.
     */
    suspend fun indexProjectDocuments(project: ProjectDocument): DocumentIndexingResult =
        withContext(Dispatchers.IO) {
            logger.info { "Starting unified document indexing for project: ${project.name}" }

            val projectPath = Paths.get(project.path)
            if (!Files.exists(projectPath)) {
                logger.warn { "Project path does not exist: ${project.path}" }
                return@withContext DocumentIndexingResult(0, 0, 1)
            }

            indexingMonitorService.addStepLog(
                project.id,
                IndexingStepType.DOCUMENTATION,
                "Starting unified document indexing for project: ${project.name}",
            )

            val gitCommitHash =
                historicalVersioningService.getCurrentGitCommitHash(projectPath)
                    ?: "unified-docs-${System.currentTimeMillis()}"

            val documentFiles = findAllDocumentFiles(projectPath)
            logger.info { "Found ${documentFiles.size} document files to process" }

            var processedCount = 0
            var skippedCount = 0
            var errorCount = 0

            for ((index, docFile) in documentFiles.withIndex()) {
                try {
                    val relativePath = projectPath.relativize(docFile).toString()
                    indexingMonitorService.addStepLog(
                        project.id,
                        IndexingStepType.DOCUMENTATION,
                        "Processing document (${index + 1}/${documentFiles.size}): $relativePath",
                    )

                    val processingResult = tikaDocumentProcessor.processDocument(docFile)

                    if (!processingResult.success || processingResult.plainText.isBlank()) {
                        skippedCount++
                        indexingMonitorService.addStepLog(
                            project.id,
                            IndexingStepType.DOCUMENTATION,
                            "⚠ Skipped file (extraction failed): $relativePath",
                        )
                        continue
                    }

                    val shouldIndex =
                        ragIndexingStatusService.shouldIndexFile(
                            projectId = project.id,
                            filePath = relativePath,
                            gitCommitHash = gitCommitHash,
                            fileContent = processingResult.plainText.toByteArray(),
                        )

                    if (!shouldIndex) {
                        skippedCount++
                        indexingMonitorService.addStepLog(
                            project.id,
                            IndexingStepType.DOCUMENTATION,
                            "⚠ Skipped already indexed file: $relativePath",
                        )
                        continue
                    }

                    // Start indexing status tracking
                    ragIndexingStatusService.startIndexing(
                        projectId = project.id,
                        filePath = relativePath,
                        gitCommitHash = gitCommitHash,
                        fileContent = processingResult.plainText.toByteArray(),
                        language = processingResult.metadata.language ?: inferDocumentType(docFile),
                        module = "unified-documents",
                    )

                    // Index the document using atomic sentence splitting
                    indexDocumentContent(project, docFile, processingResult, gitCommitHash)

                    processedCount++
                    indexingMonitorService.addStepLog(
                        project.id,
                        IndexingStepType.DOCUMENTATION,
                        "✓ Successfully indexed document: $relativePath",
                    )
                } catch (e: Exception) {
                    val relativePath = projectPath.relativize(docFile).toString()
                    errorCount++
                    indexingMonitorService.addStepLog(
                        project.id,
                        IndexingStepType.DOCUMENTATION,
                        "✗ Failed to index document: $relativePath - ${e.message}",
                    )
                    logger.error(e) { "Failed to index document: ${docFile.pathString}" }
                }
            }

            indexingMonitorService.addStepLog(
                project.id,
                IndexingStepType.DOCUMENTATION,
                "Unified document indexing completed: Processed: $processedCount, Skipped: $skippedCount, Errors: $errorCount",
            )

            val result = DocumentIndexingResult(processedCount, skippedCount, errorCount)
            logger.info { "Unified document indexing completed: $result" }
            result
        }

    /**
     * Indexes URL-based documentation.
     */
    suspend fun indexUrlDocuments(
        project: ProjectDocument,
        documentationUrls: List<String>,
    ): DocumentIndexingResult =
        withContext(Dispatchers.IO) {
            logger.info { "Starting URL document indexing for ${documentationUrls.size} URLs" }

            val gitCommitHash =
                historicalVersioningService.getCurrentGitCommitHash(Paths.get(project.path))
                    ?: "url-docs-${System.currentTimeMillis()}"

            var processedCount = 0
            var skippedCount = 0
            var errorCount = 0

            for (url in documentationUrls) {
                try {
                    val content = fetchUrlContent(url)
                    if (content.isBlank()) {
                        skippedCount++
                        continue
                    }

                    val urlPath = "url-docs/${extractUrlIdentifier(url)}"
                    val shouldIndex =
                        ragIndexingStatusService.shouldIndexFile(
                            projectId = project.id,
                            filePath = urlPath,
                            gitCommitHash = gitCommitHash,
                            fileContent = content.toByteArray(),
                        )

                    if (!shouldIndex) {
                        skippedCount++
                        continue
                    }

                    ragIndexingStatusService.startIndexing(
                        projectId = project.id,
                        filePath = urlPath,
                        gitCommitHash = gitCommitHash,
                        fileContent = content.toByteArray(),
                        language = inferUrlDocumentationType(url),
                        module = "unified-url-documents",
                    )

                    indexUrlContent(project, url, content, gitCommitHash)
                    processedCount++
                } catch (e: Exception) {
                    logger.error(e) { "Failed to index URL: $url" }
                    errorCount++
                }
            }

            DocumentIndexingResult(processedCount, skippedCount, errorCount)
        }

    /**
     * Indexes document content using LLM-based atomic sentence splitting.
     */
    private suspend fun indexDocumentContent(
        project: ProjectDocument,
        docFile: Path,
        processingResult: TikaDocumentProcessor.DocumentProcessingResult,
        gitCommitHash: String,
    ) {
        val documentType = inferDocumentType(docFile)
        val sourceType = determineSourceType(docFile, documentType)

        // Use LLM to split content into atomic sentences
        val sentences = splitContentIntoAtomicSentences(processingResult.plainText)

        for ((index, sentence) in sentences.withIndex()) {
            if (sentence.trim().isEmpty()) continue

            val ragDocument =
                RagDocument(
                    projectId = project.id,
                    clientId = project.clientId,
                    summary = sentence.trim(),
                    ragSourceType = sourceType,
                    language = processingResult.metadata.language ?: documentType,
                    path = docFile.pathString,
                    gitCommitHash = gitCommitHash,
                    chunkId = index,
                    chunkOf = sentences.size,
                )

            val embedding = embeddingGateway.callEmbedding(ModelType.EMBEDDING_TEXT, sentence.trim())
            vectorStorage.store(ModelType.EMBEDDING_TEXT, ragDocument, embedding)
        }
    }

    /**
     * Indexes URL content using LLM-based atomic sentence splitting.
     */
    private suspend fun indexUrlContent(
        project: ProjectDocument,
        url: String,
        content: String,
        gitCommitHash: String,
    ) {
        val sentences = splitContentIntoAtomicSentences(content)

        for ((index, sentence) in sentences.withIndex()) {
            if (sentence.trim().isEmpty()) continue

            val ragDocument =
                RagDocument(
                    projectId = project.id,
                    clientId = project.clientId,
                    summary = sentence.trim(),
                    ragSourceType = RagSourceType.DOCUMENTATION,
                    path = url,
                    gitCommitHash = gitCommitHash,
                    chunkId = index,
                    chunkOf = sentences.size,
                )

            val embedding = embeddingGateway.callEmbedding(ModelType.EMBEDDING_TEXT, sentence.trim())
            vectorStorage.store(ModelType.EMBEDDING_TEXT, ragDocument, embedding)
        }
    }

    /**
     * Uses LLM to split content into atomic, independent sentences for RAG storage.
     * This replaces manual text splitting logic with consistent AI-based approach.
     */
    private suspend fun splitContentIntoAtomicSentences(content: String): List<String> {
        if (content.trim().isEmpty()) return emptyList()

        val result =
            llmGateway.callLlm(
                type = PromptTypeEnum.CONTENT_SPLIT_SENTENCES,
                responseSchema = ContentSentenceSplittingResponse(),
                mappingValue = mapOf("content" to content.take(8000)), // Limit content size for LLM
            )

        return result.result.sentences.filter { it.trim().isNotEmpty() }
    }

    private suspend fun fetchUrlContent(url: String): String {
        val client =
            HttpClient
                .newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build()

        val request =
            HttpRequest
                .newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(60))
                .GET()
                .build()

        val response = client.send(request, HttpResponse.BodyHandlers.ofString())

        return when {
            response.statusCode() != 200 -> {
                logger.warn { "Failed to fetch URL $url: HTTP ${response.statusCode()}" }
                ""
            }

            response
                .headers()
                .firstValue("content-type")
                .orElse("")
                .contains("text/html") -> {
                extractTextFromHtml(response.body())
            }

            else -> response.body()
        }
    }

    private fun extractTextFromHtml(html: String): String =
        html
            .replace(Regex("<script[^>]*>.*?</script>", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("<style[^>]*>.*?</style>", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("<[^>]+>"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun findAllDocumentFiles(projectPath: Path): List<Path> =
        try {
            Files
                .walk(projectPath)
                .filter { it.isRegularFile() }
                .filter { isDocumentFile(it) }
                .toList()
        } catch (e: Exception) {
            logger.error(e) { "Failed to scan project directory: $projectPath" }
            emptyList()
        }

    private fun isDocumentFile(path: Path): Boolean {
        val fileName = path.fileName.toString().lowercase()
        val documentExtensions =
            setOf(
                "md",
                "txt",
                "rst",
                "adoc",
                "asciidoc",
                "pdf",
                "doc",
                "docx",
                "html",
                "htm",
                "xml",
                "json",
                "yaml",
                "yml",
            )
        val meetingKeywords =
            setOf(
                "meeting",
                "transcript",
                "notes",
                "standup",
                "scrum",
                "review",
                "retrospective",
                "planning",
                "demo",
                "discussion",
            )

        val hasDocumentExtension = documentExtensions.any { fileName.endsWith(".$it") }
        val hasMeetingKeyword = meetingKeywords.any { fileName.contains(it) }

        return hasDocumentExtension || hasMeetingKeyword
    }

    private fun inferDocumentType(path: Path): String {
        val fileName = path.fileName.toString().lowercase()
        return when {
            fileName.contains("meeting") || fileName.contains("transcript") -> "meeting"
            fileName.endsWith(".md") -> "markdown"
            fileName.endsWith(".pdf") -> "pdf"
            fileName.endsWith(".html") || fileName.endsWith(".htm") -> "html"
            fileName.endsWith(".json") -> "json"
            fileName.endsWith(".yaml") || fileName.endsWith(".yml") -> "yaml"
            else -> "text"
        }
    }

    private fun determineSourceType(
        path: Path,
        documentType: String,
    ): RagSourceType =
        when (documentType) {
            "meeting" -> RagSourceType.MEETING_TRANSCRIPT
            else -> RagSourceType.DOCUMENTATION
        }

    private fun inferUrlDocumentationType(url: String): String =
        when {
            url.contains("confluence") -> "confluence"
            url.contains("wiki") -> "wiki"
            url.contains("github") -> "github"
            else -> "web"
        }

    private fun extractUrlIdentifier(url: String): String =
        try {
            URI.create(url).let { uri ->
                "${uri.host}${uri.path}".replace("[^a-zA-Z0-9._-]".toRegex(), "_")
            }
        } catch (e: Exception) {
            url.hashCode().toString()
        }
}
