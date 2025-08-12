package com.jervis.service.indexer.chunking

import mu.KotlinLogging
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

/**
 * Strategy for chunking text content with semantic coherence.
 * This strategy attempts to split text at natural boundaries like paragraphs and sentences.
 */
@Component
class TextChunkStrategy(
    @Value("\${chunking.text.max-chunk-size:1024}") private val defaultMaxChunkSize: Int,
    @Value("\${chunking.text.overlap-size:200}") private val defaultOverlapSize: Int,
) : ChunkStrategy {
    private val logger = KotlinLogging.logger {}

    private val textFormats =
        setOf(
            "md",
            "markdown",
            "txt",
            "text",
            "html",
            "htm",
            "rst",
            "adoc",
            "tex",
            "rtf",
            "csv",
            "log",
        )

    override fun splitContent(
        content: String,
        metadata: Map<String, String>,
        maxChunkSize: Int,
        overlapSize: Int,
    ): List<Chunk> {
        val format = metadata["format"] ?: "text"
        val actualMaxChunkSize = if (maxChunkSize > 0) maxChunkSize else defaultMaxChunkSize
        val actualOverlapSize = if (overlapSize > 0) overlapSize else defaultOverlapSize

        logger.debug { "Chunking text with format: $format, maxChunkSize: $actualMaxChunkSize, overlapSize: $actualOverlapSize" }

        // Special handling for different formats
        return when (format.lowercase()) {
            "md", "markdown" -> chunkMarkdownText(content, actualMaxChunkSize, actualOverlapSize)
            "html", "htm" -> chunkHtmlText(content, actualMaxChunkSize, actualOverlapSize)
            else -> chunkGenericText(content, actualMaxChunkSize, actualOverlapSize)
        }
    }

    override fun canHandle(contentType: String): Boolean = contentType.lowercase() in textFormats

    /**
     * Chunk Markdown text by headings and paragraphs
     */
    private fun chunkMarkdownText(
        content: String,
        maxChunkSize: Int,
        overlapSize: Int,
    ): List<Chunk> {
        val chunks = mutableListOf<Chunk>()
        val lines = content.lines()

        // First, identify all headings and their levels
        val headings = mutableListOf<Triple<Int, Int, String>>() // (line number, level, heading text)

        lines.forEachIndexed { index, line ->
            if (line.startsWith("#")) {
                val level = line.takeWhile { it == '#' }.length
                val headingText = line.substring(level).trim()
                headings.add(Triple(index, level, headingText))
            }
        }

        // If no headings, fall back to generic text chunking
        if (headings.isEmpty()) {
            return chunkGenericText(content, maxChunkSize, overlapSize)
        }

        // Process each section (from one heading to the next)
        for (i in 0 until headings.size) {
            val (startLine, level, headingText) = headings[i]
            val endLine = if (i < headings.size - 1) headings[i + 1].first - 1 else lines.size - 1

            // Extract section content
            val sectionLines = lines.subList(startLine, endLine + 1)
            val sectionContent = sectionLines.joinToString("\n")

            // Check if section needs to be split further
            val sectionWordCount = countWords(sectionContent)

            if (sectionWordCount <= maxChunkSize) {
                // Section fits in one chunk
                chunks.add(
                    Chunk(
                        content = sectionContent,
                        metadata =
                            mapOf(
                                "type" to "section",
                                "heading" to headingText,
                                "level" to level,
                                "start_line" to startLine,
                                "end_line" to endLine,
                            ),
                    ),
                )
            } else {
                // Section needs to be split into smaller chunks
                val paragraphs = sectionContent.split("\n\n")

                // Create chunks from paragraphs with overlap
                val paragraphChunks =
                    createChunksFromParagraphs(
                        paragraphs,
                        maxChunkSize,
                        overlapSize,
                        baseMetadata =
                            mapOf(
                                "type" to "section",
                                "heading" to headingText,
                                "level" to level,
                                "start_line" to startLine,
                                "end_line" to endLine,
                            ),
                    )

                chunks.addAll(paragraphChunks)
            }
        }

        return chunks
    }

    /**
     * Chunk HTML text by extracting text content
     */
    private fun chunkHtmlText(
        content: String,
        maxChunkSize: Int,
        overlapSize: Int,
    ): List<Chunk> {
        // For simplicity, we'll extract text from HTML and chunk it as generic text
        // A more sophisticated implementation would use JSoup to parse the HTML structure

        // Remove HTML tags and decode entities
        val textContent =
            content
                .replace(Regex("<[^>]*>"), " ") // Replace tags with space
                .replace(Regex("&[a-zA-Z]+;"), " ") // Replace entities with space
                .replace(Regex("\\s+"), " ") // Normalize whitespace
                .trim()

        return chunkGenericText(textContent, maxChunkSize, overlapSize)
    }

    /**
     * Chunk generic text by paragraphs and sentences
     */
    private fun chunkGenericText(
        content: String,
        maxChunkSize: Int,
        overlapSize: Int,
    ): List<Chunk> {
        // Split by paragraphs (double newlines)
        val paragraphs = content.split("\n\n")

        return createChunksFromParagraphs(paragraphs, maxChunkSize, overlapSize)
    }

    /**
     * Create chunks from paragraphs with overlap
     */
    private fun createChunksFromParagraphs(
        paragraphs: List<String>,
        maxChunkSize: Int,
        overlapSize: Int,
        baseMetadata: Map<String, Any> = emptyMap(),
    ): List<Chunk> {
        val chunks = mutableListOf<Chunk>()
        var currentChunk = StringBuilder()
        var currentWordCount = 0
        var chunkIndex = 0

        for (paragraph in paragraphs) {
            if (paragraph.isBlank()) continue

            val paragraphWordCount = countWords(paragraph)

            // If adding this paragraph would exceed the max chunk size, create a new chunk
            if (currentWordCount + paragraphWordCount > maxChunkSize && currentChunk.isNotEmpty()) {
                chunks.add(
                    Chunk(
                        content = currentChunk.toString().trim(),
                        metadata =
                            baseMetadata +
                                mapOf(
                                    "chunk_index" to chunkIndex,
                                ),
                    ),
                )

                // Start a new chunk with overlap
                val overlapContent = getOverlapContent(currentChunk.toString(), overlapSize)
                currentChunk = StringBuilder(overlapContent)
                currentWordCount = countWords(overlapContent)
                chunkIndex++
            }

            // Add paragraph to current chunk
            currentChunk.append(paragraph).append("\n\n")
            currentWordCount += paragraphWordCount + 2 // +2 for the newlines
        }

        // Add the last chunk if not empty
        if (currentChunk.isNotEmpty()) {
            chunks.add(
                Chunk(
                    content = currentChunk.toString().trim(),
                    metadata =
                        baseMetadata +
                            mapOf(
                                "chunk_index" to chunkIndex,
                            ),
                ),
            )
        }

        return chunks
    }

    /**
     * Get overlap content from the end of a chunk
     */
    private fun getOverlapContent(
        content: String,
        overlapSize: Int,
    ): String {
        if (content.isBlank() || overlapSize <= 0) return ""

        // Try to get overlap by sentences first
        val sentences = content.split(Regex("(?<=[.!?])\\s+"))

        if (sentences.size <= 1) {
            // If no sentence boundaries, just take the last N words
            val words = content.split(Regex("\\s+"))
            if (words.size <= overlapSize) return content

            val overlapWords = words.takeLast(overlapSize)
            return overlapWords.joinToString(" ")
        }

        // Build overlap from sentences
        val overlapBuilder = StringBuilder()
        var overlapWordCount = 0

        for (i in sentences.size - 1 downTo 0) {
            val sentence = sentences[i]
            val sentenceWordCount = countWords(sentence)

            if (overlapWordCount + sentenceWordCount <= overlapSize || overlapBuilder.isEmpty()) {
                overlapBuilder.insert(0, sentence + " ")
                overlapWordCount += sentenceWordCount
            } else {
                break
            }
        }

        return overlapBuilder.toString().trim()
    }

    /**
     * Count words in a string
     */
    private fun countWords(text: String): Int = text.split(Regex("\\s+")).count { it.isNotBlank() }
}
