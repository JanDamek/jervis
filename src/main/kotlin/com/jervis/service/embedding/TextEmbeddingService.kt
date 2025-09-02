package com.jervis.service.embedding

import com.jervis.domain.model.ModelType
import com.jervis.domain.rag.RagDocument
import com.jervis.domain.rag.RagDocumentType
import com.jervis.domain.rag.RagSourceType
import com.jervis.entity.mongo.ProjectDocument
import com.jervis.repository.vector.VectorStorageRepository
import com.jervis.service.gateway.EmbeddingGateway
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import org.springframework.stereotype.Service
import java.nio.file.Path
import kotlin.io.path.pathString

/**
 * Service dedicated to text embedding operations.
 * Handles text content processing and embedding generation using text models.
 */
@Service
class TextEmbeddingService(
    private val embeddingGateway: EmbeddingGateway,
    private val vectorStorage: VectorStorageRepository,
) {
    private val logger = KotlinLogging.logger {}

    /**
     * Result of text embedding operation
     */
    data class TextEmbeddingResult(
        val processedSentences: Int,
        val skippedSentences: Int,
        val errorSentences: Int,
    )

    /**
     * Process text content and generate embeddings for text documents
     */
    suspend fun processTextContent(
        project: ProjectDocument,
        content: String,
        filePath: Path,
        projectPath: Path,
    ): TextEmbeddingResult =
        withContext(Dispatchers.Default) {
            logger.debug { "Processing text content for file: ${filePath.fileName}" }

            val sentences = splitIntoSentences(content)
            var processedSentences = 0
            var skippedSentences = 0
            var errorSentences = 0

            // Process sentences sequentially to avoid concurrent modification issues
            for ((index, sentence) in sentences.withIndex()) {
                try {
                    if (sentence.trim().length > 20) { // Skip very short sentences
                        // Generate text embedding for semantic search
                        val embedding = embeddingGateway.callEmbedding(ModelType.EMBEDDING_TEXT, sentence)

                        // Create RAG document for semantic content
                        val ragDocument =
                            RagDocument(
                                projectId = project.id!!,
                                documentType = RagDocumentType.TEXT,
                                ragSourceType = RagSourceType.FILE,
                                pageContent = sentence,
                                source = "${filePath.pathString}#sentence-$index",
                                path = projectPath.relativize(filePath).pathString,
                                module = filePath.fileName.toString(),
                            )

                        // Store in TEXT vector store
                        vectorStorage.store(ModelType.EMBEDDING_TEXT, ragDocument, embedding)
                        processedSentences++
                    } else {
                        skippedSentences++
                    }
                } catch (e: Exception) {
                    logger.warn(e) { "Failed to process text sentence from ${filePath.fileName}" }
                    errorSentences++
                }
            }

            logger.info {
                "Text processing completed for ${filePath.fileName}: " +
                    "processed=$processedSentences, skipped=$skippedSentences, errors=$errorSentences"
            }

            TextEmbeddingResult(processedSentences, skippedSentences, errorSentences)
        }

    /**
     * Process multiple text contents in parallel
     */
    suspend fun processTextContents(
        project: ProjectDocument,
        textContents: Map<Path, String>,
        projectPath: Path,
    ): TextEmbeddingResult =
        withContext(Dispatchers.Default) {
            logger.info { "Processing ${textContents.size} text files for project: ${project.name}" }

            val jobs =
                textContents.map { (filePath, content) ->
                    async {
                        processTextContent(project, content, filePath, projectPath)
                    }
                }

            val results = jobs.awaitAll()
            val totalResult =
                results.fold(TextEmbeddingResult(0, 0, 0)) { acc, result ->
                    TextEmbeddingResult(
                        processedSentences = acc.processedSentences + result.processedSentences,
                        skippedSentences = acc.skippedSentences + result.skippedSentences,
                        errorSentences = acc.errorSentences + result.errorSentences,
                    )
                }

            logger.info {
                "Text processing batch completed for project ${project.name}: " +
                    "total processed=${totalResult.processedSentences}, " +
                    "total skipped=${totalResult.skippedSentences}, " +
                    "total errors=${totalResult.errorSentences}"
            }

            totalResult
        }

    /**
     * Generate embedding for a single text
     */
    suspend fun generateTextEmbedding(text: String): List<Float> = embeddingGateway.callEmbedding(ModelType.EMBEDDING_TEXT, text)

    /**
     * Split content into sentences for better semantic indexing
     */
    private fun splitIntoSentences(content: String): List<String> {
        // Simple sentence splitting - can be enhanced with more sophisticated NLP
        return content
            .split(Regex("[.!?]+"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

    /**
     * Check if content should be processed as text
     */
    fun isTextContent(filePath: Path): Boolean {
        val fileName = filePath.fileName.toString().lowercase()
        val textExtensions =
            setOf(
                "txt",
                "md",
                "rst",
                "adoc",
                "tex",
                "rtf",
                "csv",
                "tsv",
                "json",
                "xml",
                "yaml",
                "yml",
                "properties",
                "conf",
                "ini",
                "cfg",
            )

        return textExtensions.any { fileName.endsWith(".$it") } ||
            fileName.contains("readme") ||
            fileName.contains("changelog") ||
            fileName.contains("license")
    }
}
