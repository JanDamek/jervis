package com.jervis.service.indexing

import com.jervis.domain.model.ModelType
import com.jervis.domain.rag.RagDocument
import com.jervis.domain.rag.RagSourceType
import com.jervis.entity.mongo.ProjectDocument
import com.jervis.repository.vector.VectorStorageRepository
import com.jervis.service.document.TikaDocumentProcessor
import com.jervis.service.gateway.EmbeddingGateway
import com.jervis.service.indexing.monitoring.IndexingStepType
import com.jervis.service.rag.RagIndexingStatusService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
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
 * Service for indexing documentation from local paths and external URLs.
 * Supports various documentation systems including Confluence and similar platforms.
 */
@Service
class DocumentationIndexingService(
    private val embeddingGateway: EmbeddingGateway,
    private val vectorStorage: VectorStorageRepository,
    private val ragIndexingStatusService: RagIndexingStatusService,
    private val historicalVersioningService: HistoricalVersioningService,
    private val indexingMonitorService: com.jervis.service.indexing.monitoring.IndexingMonitorService,
    private val tikaDocumentProcessor: TikaDocumentProcessor,
) {
    private val logger = KotlinLogging.logger {}
    private val httpClient =
        HttpClient
            .newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build()

    /**
     * Result of documentation indexing operation
     */
    data class DocumentationIndexingResult(
        val processedDocuments: Int,
        val skippedDocuments: Int,
        val errorDocuments: Int,
    )

    /**
     * Index all documentation sources for a project
     */
    suspend fun indexProjectDocumentation(project: ProjectDocument): DocumentationIndexingResult =
        withContext(Dispatchers.IO) {
            logger.info { "Starting documentation indexing for project: ${project.name}" }

            val operations = mutableListOf<suspend () -> DocumentationIndexingResult>()

            // Add local documentation path indexing if configured
            project.documentationPath?.let { docPath ->
                operations.add { indexLocalDocumentation(project, docPath) }
            }

            // Add URL-based documentation indexing if configured
            if (project.documentationUrls.isNotEmpty()) {
                operations.add { indexUrlDocumentation(project, project.documentationUrls) }
            }

            if (operations.isEmpty()) {
                logger.info { "No documentation sources configured for project: ${project.name}" }
                return@withContext DocumentationIndexingResult(0, 0, 0)
            }

            try {
                // Execute all documentation indexing operations in parallel
                val results =
                    operations
                        .map { operation ->
                            async { operation() }
                        }.awaitAll()

                // Aggregate results
                val totalProcessed = results.sumOf { it.processedDocuments }
                val totalSkipped = results.sumOf { it.skippedDocuments }
                val totalErrors = results.sumOf { it.errorDocuments }

                logger.info {
                    "Documentation indexing completed for project: ${project.name} - " +
                        "Processed: $totalProcessed, Skipped: $totalSkipped, Errors: $totalErrors"
                }

                DocumentationIndexingResult(totalProcessed, totalSkipped, totalErrors)
            } catch (e: Exception) {
                logger.error(e) { "Error during documentation indexing for project: ${project.name}" }
                DocumentationIndexingResult(0, 0, 1)
            }
        }

    /**
     * Index documentation from local file system path
     */
    private suspend fun indexLocalDocumentation(
        project: ProjectDocument,
        documentationPath: String,
    ): DocumentationIndexingResult =
        withContext(Dispatchers.IO) {
            logger.info { "Indexing local documentation from path: $documentationPath for project: ${project.name}" }

            val docPath = Paths.get(documentationPath)
            if (!Files.exists(docPath)) {
                logger.warn { "Documentation path does not exist: $documentationPath" }
                return@withContext DocumentationIndexingResult(0, 0, 1)
            }

            // Get current git commit hash for tracking
            val gitCommitHash =
                historicalVersioningService.getCurrentGitCommitHash(Paths.get(project.path))
                    ?: "local-docs-${System.currentTimeMillis()}"

            var processedDocs = 0
            var skippedDocs = 0
            var errorDocs = 0

            try {
                val documentationFiles = mutableListOf<Path>()

                Files
                    .walk(docPath)
                    .filter { it.isRegularFile() }
                    .filter { isDocumentationFile(it) }
                    .forEach { documentationFiles.add(it) }

                logger.info { "Found ${documentationFiles.size} documentation files to process" }
                indexingMonitorService.addStepLog(
                    project.id,
                    IndexingStepType.DOCUMENTATION,
                    "Found ${documentationFiles.size} documentation files to process",
                )

                for ((index, docFile) in documentationFiles.withIndex()) {
                    try {
                        val relativePath = docPath.relativize(docFile).toString()
                        indexingMonitorService.addStepLog(
                            project.id,
                            IndexingStepType.DOCUMENTATION,
                            "Processing documentation file (${index + 1}/${documentationFiles.size}): $relativePath",
                        )

                        val processingResult = tikaDocumentProcessor.processDocument(docFile)

                        if (!processingResult.success || processingResult.plainText.isBlank()) {
                            skippedDocs++
                            indexingMonitorService.addStepLog(
                                project.id,
                                IndexingStepType.DOCUMENTATION,
                                "⚠ Skipped file (extraction failed): $relativePath - ${processingResult.errorMessage ?: "Empty content"}",
                            )
                            continue
                        }

                        // Check if already indexed to prevent duplicates
                        val shouldIndex =
                            ragIndexingStatusService.shouldIndexFile(
                                projectId = project.id,
                                filePath = "docs/$relativePath",
                                gitCommitHash = gitCommitHash,
                                fileContent = processingResult.plainText.toByteArray(),
                            )

                        if (!shouldIndex) {
                            skippedDocs++
                            indexingMonitorService.addStepLog(
                                project.id,
                                IndexingStepType.DOCUMENTATION,
                                "⚠ Skipped already indexed file: $relativePath",
                            )
                            logger.debug { "Skipping already indexed documentation file: $relativePath" }
                            continue
                        }

                        // Track indexing status
                        ragIndexingStatusService.startIndexing(
                            projectId = project.id,
                            filePath = "docs/$relativePath",
                            gitCommitHash = gitCommitHash,
                            fileContent = processingResult.plainText.toByteArray(),
                            language = processingResult.metadata.language ?: inferDocumentationType(docFile),
                            module = "documentation",
                        )

                        // Split content into atomic sentences with location tracking
                        val sentencesWithLocation =
                            tikaDocumentProcessor.splitIntoSentencesWithLocation(
                                processingResult.plainText,
                                processingResult.metadata,
                            )

                        logger.debug { "Split document $relativePath into ${sentencesWithLocation.size} atomic sentences" }

                        // Create individual RAG documents for each sentence
                        for ((index, sentenceWithLocation) in sentencesWithLocation.withIndex()) {
                            val ragDocument =
                                RagDocument(
                                    projectId = project.id,
                                    summary =
                                        buildDocumentationSentenceContent(
                                            docFile,
                                            sentenceWithLocation,
                                            processingResult.metadata,
                                        ),
                                    clientId = project.clientId,
                                    ragSourceType = RagSourceType.DOCUMENTATION,
                                    path = relativePath,
                                    language = processingResult.metadata.language ?: inferDocumentationType(docFile),
                                    gitCommitHash = gitCommitHash,
                                    chunkId = index,
                                    symbolName = "doc-${docFile.fileName}",
                                )

                            // Generate embedding and store
                            val embedding =
                                embeddingGateway.callEmbedding(ModelType.EMBEDDING_TEXT, ragDocument.summary)
                            vectorStorage.store(ModelType.EMBEDDING_TEXT, ragDocument, embedding)
                        }

                        processedDocs++
                        indexingMonitorService.addStepLog(
                            project.id,
                            IndexingStepType.DOCUMENTATION,
                            "✓ Successfully indexed documentation file: $relativePath",
                        )
                        logger.debug { "Successfully indexed documentation file: $relativePath" }
                    } catch (e: Exception) {
                        val relativePath = docPath.relativize(docFile).toString()
                        indexingMonitorService.addStepLog(
                            project.id,
                            IndexingStepType.DOCUMENTATION,
                            "✗ Failed to index documentation file: $relativePath - ${e.message}",
                        )
                        logger.warn(e) { "Failed to index documentation file: ${docFile.pathString}" }
                        errorDocs++
                    }
                }
            } catch (e: Exception) {
                logger.error(e) { "Error during local documentation indexing for project: ${project.name}" }
                errorDocs++
            }

            logger.info {
                "Local documentation indexing completed for project: ${project.name} - " +
                    "Processed: $processedDocs, Skipped: $skippedDocs, Errors: $errorDocs"
            }

            DocumentationIndexingResult(processedDocs, skippedDocs, errorDocs)
        }

    /**
     * Index documentation from external URLs (Confluence, wikis, etc.)
     */
    private suspend fun indexUrlDocumentation(
        project: ProjectDocument,
        documentationUrls: List<String>,
    ): DocumentationIndexingResult =
        withContext(Dispatchers.IO) {
            logger.info { "Indexing URL-based documentation for project: ${project.name}, URLs: ${documentationUrls.size}" }

            // Get current git commit hash for tracking
            val gitCommitHash =
                historicalVersioningService.getCurrentGitCommitHash(Paths.get(project.path))
                    ?: "url-docs-${System.currentTimeMillis()}"

            var processedDocs = 0
            var skippedDocs = 0
            var errorDocs = 0

            for ((_, url) in documentationUrls.withIndex()) {
                try {
                    logger.debug { "Processing documentation URL: $url" }

                    val content = fetchUrlContent(url)
                    if (content.isBlank()) {
                        skippedDocs++
                        logger.warn { "Empty content from URL: $url" }
                        continue
                    }

                    val urlPath = "url-docs/${extractUrlIdentifier(url)}"

                    // Check if already indexed to prevent duplicates
                    val shouldIndex =
                        ragIndexingStatusService.shouldIndexFile(
                            projectId = project.id,
                            filePath = urlPath,
                            gitCommitHash = gitCommitHash,
                            fileContent = content.toByteArray(),
                        )

                    if (!shouldIndex) {
                        skippedDocs++
                        logger.debug { "Skipping already indexed documentation URL: $url" }
                        continue
                    }

                    // Track indexing status
                    ragIndexingStatusService.startIndexing(
                        projectId = project.id,
                        filePath = urlPath,
                        gitCommitHash = gitCommitHash,
                        fileContent = content.toByteArray(),
                        language = inferUrlDocumentationType(url),
                        module = "documentation-urls",
                    )

                    // Create RAG document
                    val ragDocument =
                        RagDocument(
                            projectId = project.id,
                            summary = buildUrlDocumentationContent(url, content),
                            clientId = project.clientId,
                            ragSourceType = RagSourceType.URL,
                            path = urlPath,
                            language = inferUrlDocumentationType(url),
                            gitCommitHash = gitCommitHash,
                        )

                    // Generate embedding and store
                    val embedding = embeddingGateway.callEmbedding(ModelType.EMBEDDING_TEXT, ragDocument.summary)
                    vectorStorage.store(ModelType.EMBEDDING_TEXT, ragDocument, embedding)

                    processedDocs++
                    logger.debug { "Successfully indexed documentation URL: $url" }
                } catch (e: Exception) {
                    logger.warn(e) { "Failed to index documentation URL: $url" }
                    errorDocs++
                }
            }

            logger.info {
                "URL documentation indexing completed for project: ${project.name} - " +
                    "Processed: $processedDocs, Skipped: $skippedDocs, Errors: $errorDocs"
            }

            DocumentationIndexingResult(processedDocs, skippedDocs, errorDocs)
        }

    /**
     * Fetch content from URL with proper error handling and timeout
     */
    private suspend fun fetchUrlContent(url: String): String =
        withContext(Dispatchers.IO) {
            try {
                val request =
                    HttpRequest
                        .newBuilder()
                        .uri(URI.create(url))
                        .timeout(Duration.ofSeconds(30))
                        .header("User-Agent", "Jervis-Documentation-Indexer/1.0")
                        .GET()
                        .build()

                val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())

                if (response.statusCode() != 200) {
                    logger.warn { "HTTP ${response.statusCode()} when fetching URL: $url" }
                    return@withContext ""
                }

                // Basic HTML content extraction (could be enhanced with proper HTML parsing)
                val content = response.body()
                return@withContext extractTextFromHtml(content)
            } catch (e: Exception) {
                logger.error(e) { "Error fetching content from URL: $url" }
                throw e
            }
        }

    /**
     * Extract text content from HTML (basic implementation)
     */
    private fun extractTextFromHtml(html: String): String =
        html
            .replace(Regex("<script[^>]*>.*?</script>", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("<style[^>]*>.*?</style>", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("<[^>]+>"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    /**
     * Check if file is a documentation file based on extension
     */
    private fun isDocumentationFile(path: Path): Boolean {
        val fileName = path.fileName.toString().lowercase()
        return fileName.endsWith(".md") ||
            fileName.endsWith(".txt") ||
            fileName.endsWith(".rst") ||
            fileName.endsWith(".adoc") ||
            fileName.endsWith(".asciidoc") ||
            fileName.endsWith(".html") ||
            fileName.endsWith(".htm")
    }

    /**
     * Infer documentation type from file extension
     */
    private fun inferDocumentationType(path: Path): String {
        val fileName = path.fileName.toString().lowercase()
        return when {
            fileName.endsWith(".md") -> "markdown"
            fileName.endsWith(".rst") -> "restructuredtext"
            fileName.endsWith(".adoc") || fileName.endsWith(".asciidoc") -> "asciidoc"
            fileName.endsWith(".html") || fileName.endsWith(".htm") -> "html"
            else -> "text"
        }
    }

    /**
     * Infer documentation type from URL
     */
    private fun inferUrlDocumentationType(url: String): String =
        when {
            url.contains("confluence") -> "confluence"
            url.contains("wiki") -> "wiki"
            url.contains("github.com") && url.contains("/wiki/") -> "github-wiki"
            url.contains("notion.so") -> "notion"
            url.contains("gitbook") -> "gitbook"
            else -> "web-documentation"
        }

    /**
     * Extract identifier from URL for file path
     */
    private fun extractUrlIdentifier(url: String): String =
        try {
            val uri = URI.create(url)
            val path = uri.path?.takeIf { it.isNotEmpty() } ?: "root"
            val host = uri.host ?: "unknown-host"
            "${host}$path".replace("[^a-zA-Z0-9-_/]".toRegex(), "-")
        } catch (_: Exception) {
            "url-${url.hashCode()}"
        }

    /**
     * Build formatted content for URL documentation
     */
    private fun buildUrlDocumentationContent(
        url: String,
        content: String,
    ): String =
        buildString {
            appendLine("Documentation: ${extractUrlIdentifier(url)}")
            appendLine("=".repeat(60))
            appendLine("URL: $url")
            appendLine("Type: ${inferUrlDocumentationType(url)}")
            appendLine()
            appendLine("Content:")
            appendLine(content)
            appendLine()
            appendLine("---")
            appendLine("Source: External Documentation URL")
            appendLine("Platform: ${inferUrlDocumentationType(url)}")
            appendLine("Indexed as: Documentation Content")
        }

    /**
     * Build formatted content for individual documentation sentences with metadata and location tracking
     */
    private fun buildDocumentationSentenceContent(
        docFile: Path,
        sentenceWithLocation: TikaDocumentProcessor.SentenceWithLocation,
        metadata: TikaDocumentProcessor.DocumentMetadata,
    ): String =
        buildString {
            appendLine("Documentation Sentence: ${metadata.title ?: docFile.fileName}")
            appendLine("=".repeat(60))
            appendLine("File: ${docFile.pathString}")
            appendLine("Document Type: ${metadata.contentType ?: inferDocumentationType(docFile)}")
            if (metadata.author != null) {
                appendLine("Author: ${metadata.author}")
            }
            if (metadata.language != null) {
                appendLine("Language: ${metadata.language}")
            }

            // Location tracking information
            appendLine("Location:")
            if (sentenceWithLocation.location.pageNumber != null) {
                appendLine("- Page: ${sentenceWithLocation.location.pageNumber}")
            }
            if (sentenceWithLocation.location.paragraphIndex != null) {
                appendLine("- Paragraph: ${sentenceWithLocation.location.paragraphIndex + 1}")
            }
            if (sentenceWithLocation.location.sectionTitle != null) {
                appendLine("- Section: ${sentenceWithLocation.location.sectionTitle}")
            }
            if (sentenceWithLocation.location.characterOffset != null && sentenceWithLocation.location.characterOffset > 0) {
                appendLine("- Character Offset: ${sentenceWithLocation.location.characterOffset}")
            }

            appendLine()
            appendLine("Content:")
            appendLine(sentenceWithLocation.text)
            appendLine()

            // Additional metadata if available
            if (metadata.keywords.isNotEmpty()) {
                appendLine("Keywords: ${metadata.keywords.joinToString(", ")}")
            }
            if (metadata.pageCount != null) {
                appendLine("Total Pages: ${metadata.pageCount}")
            }

            appendLine("---")
            appendLine("Source: Local Documentation File (Tika Processed)")
            appendLine("Indexed as: Atomic Documentation Sentence")
            appendLine("Searchable Location: ${sentenceWithLocation.location.documentPath}")
        }
}
