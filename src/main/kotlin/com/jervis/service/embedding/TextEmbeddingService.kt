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

            // Process chunks sequentially to avoid concurrent modification issues
            for ((index, chunk) in sentences.withIndex()) {
                try {
                    if (chunk.trim().length >= 50) { // Skip very short chunks (meaningful chunks should be much larger)
                        // Generate text embedding for semantic search
                        val embedding = embeddingGateway.callEmbedding(ModelType.EMBEDDING_TEXT, chunk)

                        // Create RAG document for semantic content
                        val relativePath =
                            try {
                                projectPath.relativize(filePath).pathString
                            } catch (e: IllegalArgumentException) {
                                // Fallback when paths are different types (e.g., synthetic vs file paths)
                                filePath.pathString
                            }
                        val ragDocument =
                            RagDocument(
                                projectId = project.id,
                                clientId = project.clientId,
                                documentType = RagDocumentType.TEXT,
                                ragSourceType = RagSourceType.FILE,
                                pageContent = chunk,
                                source = "${filePath.pathString}#chunk-$index",
                                path = relativePath,
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
     * Split content into meaningful chunks for better semantic indexing.
     * Ensures minimum chunk size of 200 characters and intelligent sentence boundary detection.
     */
    private fun splitIntoSentences(content: String): List<String> =
        splitIntoMeaningfulChunks(content, minChunkSize = 200, maxChunkSize = 1000)

    /**
     * Advanced text chunking that creates meaningful text segments.
     * Combines sentences to reach minimum chunk size while respecting sentence boundaries.
     */
    private fun splitIntoMeaningfulChunks(
        content: String,
        minChunkSize: Int = 200,
        maxChunkSize: Int = 1000,
    ): List<String> {
        if (content.length < minChunkSize) {
            return listOf(content.trim()).filter { it.isNotEmpty() }
        }

        // First, split into potential sentence boundaries with more intelligent detection
        val potentialSentences =
            content
                .split(Regex("(?<=[.!?])\\s+(?=[A-Z])")) // Split on sentence endings followed by whitespace and capital letter
                .map { it.trim() }
                .filter { it.isNotEmpty() }

        if (potentialSentences.isEmpty()) {
            return emptyList()
        }

        val chunks = mutableListOf<String>()
        var currentChunk = StringBuilder()

        for (sentence in potentialSentences) {
            val potentialLength =
                currentChunk.length + sentence.length +
                    (if (currentChunk.isNotEmpty()) 1 else 0) // space separator

            when {
                // If adding this sentence would exceed max size and we have content, finalize current chunk
                potentialLength > maxChunkSize && currentChunk.isNotEmpty() -> {
                    chunks.add(currentChunk.toString().trim())
                    currentChunk = StringBuilder(sentence)
                }
                // If single sentence is longer than max size, split it by words
                sentence.length > maxChunkSize -> {
                    // Finalize current chunk if it has content
                    if (currentChunk.isNotEmpty()) {
                        chunks.add(currentChunk.toString().trim())
                        currentChunk = StringBuilder()
                    }
                    // Split long sentence by words
                    chunks.addAll(splitLongTextByWords(sentence, maxChunkSize))
                }
                // Normal case: add sentence to current chunk
                else -> {
                    if (currentChunk.isNotEmpty()) {
                        currentChunk.append(" ")
                    }
                    currentChunk.append(sentence)
                }
            }
        }

        // Add remaining content as final chunk
        if (currentChunk.isNotEmpty()) {
            chunks.add(currentChunk.toString().trim())
        }

        // Post-process to combine small chunks
        return combineSmallChunks(chunks, minChunkSize)
    }

    /**
     * Split very long text by words when it exceeds maximum chunk size
     */
    private fun splitLongTextByWords(
        text: String,
        maxSize: Int,
    ): List<String> {
        val words = text.split(Regex("\\s+"))
        val chunks = mutableListOf<String>()
        var currentChunk = StringBuilder()

        for (word in words) {
            val potentialLength =
                currentChunk.length + word.length +
                    (if (currentChunk.isNotEmpty()) 1 else 0)

            if (potentialLength > maxSize && currentChunk.isNotEmpty()) {
                chunks.add(currentChunk.toString().trim())
                currentChunk = StringBuilder(word)
            } else {
                if (currentChunk.isNotEmpty()) {
                    currentChunk.append(" ")
                }
                currentChunk.append(word)
            }
        }

        if (currentChunk.isNotEmpty()) {
            chunks.add(currentChunk.toString().trim())
        }

        return chunks.filter { it.isNotEmpty() }
    }

    /**
     * Combine chunks that are smaller than minimum size with adjacent chunks
     */
    private fun combineSmallChunks(
        chunks: List<String>,
        minSize: Int,
    ): List<String> {
        if (chunks.isEmpty()) return chunks

        val combined = mutableListOf<String>()
        var currentCombined = StringBuilder()

        for (chunk in chunks) {
            val potentialLength =
                currentCombined.length + chunk.length +
                    (if (currentCombined.isNotEmpty()) 1 else 0)

            if (currentCombined.length >= minSize && potentialLength > minSize * 2) {
                // Current combined chunk is large enough, start new one
                combined.add(currentCombined.toString().trim())
                currentCombined = StringBuilder(chunk)
            } else {
                // Combine with current chunk
                if (currentCombined.isNotEmpty()) {
                    currentCombined.append(" ")
                }
                currentCombined.append(chunk)
            }
        }

        // Add final combined chunk
        if (currentCombined.isNotEmpty()) {
            combined.add(currentCombined.toString().trim())
        }

        return combined.filter { it.isNotEmpty() }
    }

    /**
     * Check if content should be processed as text
     */
    fun isTextContent(filePath: Path): Boolean {
        val fileName = filePath.fileName.toString().lowercase()
        val textExtensions =
            setOf(
                // Documentation and markup files
                "txt",
                "md",
                "rst",
                "adoc",
                "tex",
                "rtf",
                // Data files
                "csv",
                "tsv",
                "json",
                "xml",
                "yaml",
                "yml",
                // Configuration files
                "properties",
                "conf",
                "ini",
                "cfg",
                // Source code files - these should be indexed for semantic text analysis
                "kt",
                "kts", // Kotlin
                "java", // Java
                "py",
                "pyx", // Python
                "js",
                "jsx",
                "ts",
                "tsx", // JavaScript/TypeScript
                "go", // Go
                "rs", // Rust
                "cpp",
                "cc",
                "cxx",
                "c",
                "h",
                "hpp",
                "hxx", // C/C++
                "cs", // C#
                "php", // PHP
                "rb", // Ruby
                "swift", // Swift
                "scala", // Scala
                "clj",
                "cljs", // Clojure
                "dart", // Dart
                "r", // R
                "m", // Objective-C
                "sh",
                "bash",
                "zsh", // Shell scripts
                "sql", // SQL
                "gradle", // Gradle
                "groovy", // Groovy
                "pl",
                "pm", // Perl
                "lua", // Lua
                "vim", // Vim script
                "ps1", // PowerShell
                "dockerfile", // Docker
                "makefile", // Make
            )

        return textExtensions.any { fileName.endsWith(".$it") } ||
            fileName.contains("readme") ||
            fileName.contains("changelog") ||
            fileName.contains("license") ||
            fileName == "dockerfile" ||
            fileName == "makefile"
    }
}
