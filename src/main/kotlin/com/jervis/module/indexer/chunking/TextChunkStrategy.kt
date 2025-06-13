package com.jervis.module.indexer.chunking

import com.jervis.module.indexer.tokenizer.TokenizerFactory
import mu.KotlinLogging
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

/**
 * Strategy for chunking text content with semantic coherence.
 * This strategy attempts to split text at natural boundaries like paragraphs and sentences.
 */
@Component
class TextChunkStrategy(
    private val tokenizerFactory: TokenizerFactory,
    @Value("\${chunking.text.max-chunk-size:1024}") private val defaultMaxChunkSize: Int,
    @Value("\${chunking.text.overlap-size:200}") private val defaultOverlapSize: Int
) : ChunkStrategy {
    private val logger = KotlinLogging.logger {}
    
    private val textFormats = setOf(
        "md", "markdown", "txt", "text", "html", "htm", "rst", "adoc", "tex", "rtf", "csv", "log"
    )
    
    override fun splitContent(
        content: String,
        metadata: Map<String, String>,
        maxChunkSize: Int,
        overlapSize: Int
    ): List<Chunk> {
        val tokenizer = tokenizerFactory.getTokenizer()
        val format = metadata["format"] ?: "text"
        val actualMaxChunkSize = if (maxChunkSize > 0) maxChunkSize else defaultMaxChunkSize
        val actualOverlapSize = if (overlapSize > 0) overlapSize else defaultOverlapSize
        
        logger.debug { "Chunking text with format: $format, maxChunkSize: $actualMaxChunkSize, overlapSize: $actualOverlapSize" }
        
        // Special handling for different formats
        return when (format.lowercase()) {
            "md", "markdown" -> chunkMarkdownText(content, tokenizer, actualMaxChunkSize, actualOverlapSize)
            "html", "htm" -> chunkHtmlText(content, tokenizer, actualMaxChunkSize, actualOverlapSize)
            else -> chunkGenericText(content, tokenizer, actualMaxChunkSize, actualOverlapSize)
        }
    }
    
    override fun canHandle(contentType: String): Boolean {
        return contentType.lowercase() in textFormats
    }
    
    /**
     * Chunk Markdown text by headings and paragraphs
     */
    private fun chunkMarkdownText(
        content: String,
        tokenizer: com.jervis.module.indexer.tokenizer.Tokenizer,
        maxChunkSize: Int,
        overlapSize: Int
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
            return chunkGenericText(content, tokenizer, maxChunkSize, overlapSize)
        }
        
        // Process each section (from one heading to the next)
        for (i in 0 until headings.size) {
            val (startLine, level, headingText) = headings[i]
            val endLine = if (i < headings.size - 1) headings[i + 1].first - 1 else lines.size - 1
            
            // Extract section content
            val sectionLines = lines.subList(startLine, endLine + 1)
            val sectionContent = sectionLines.joinToString("\n")
            
            // Check if section needs to be split further
            val sectionTokenCount = tokenizer.countTokens(sectionContent)
            
            if (sectionTokenCount <= maxChunkSize) {
                // Section fits in one chunk
                chunks.add(Chunk(
                    content = sectionContent,
                    metadata = mapOf(
                        "type" to "section",
                        "heading" to headingText,
                        "level" to level,
                        "start_line" to startLine,
                        "end_line" to endLine
                    )
                ))
            } else {
                // Section needs to be split into smaller chunks
                val paragraphs = sectionContent.split("\n\n")
                
                // Create chunks from paragraphs with overlap
                val paragraphChunks = createChunksFromParagraphs(
                    paragraphs, tokenizer, maxChunkSize, overlapSize,
                    baseMetadata = mapOf(
                        "type" to "section",
                        "heading" to headingText,
                        "level" to level,
                        "start_line" to startLine,
                        "end_line" to endLine
                    )
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
        tokenizer: com.jervis.module.indexer.tokenizer.Tokenizer,
        maxChunkSize: Int,
        overlapSize: Int
    ): List<Chunk> {
        // For simplicity, we'll extract text from HTML and chunk it as generic text
        // A more sophisticated implementation would use JSoup to parse the HTML structure
        
        // Remove HTML tags and decode entities
        val textContent = content
            .replace(Regex("<[^>]*>"), " ") // Replace tags with space
            .replace(Regex("&[a-zA-Z]+;"), " ") // Replace entities with space
            .replace(Regex("\\s+"), " ") // Normalize whitespace
            .trim()
        
        return chunkGenericText(textContent, tokenizer, maxChunkSize, overlapSize)
    }
    
    /**
     * Chunk generic text by paragraphs and sentences
     */
    private fun chunkGenericText(
        content: String,
        tokenizer: com.jervis.module.indexer.tokenizer.Tokenizer,
        maxChunkSize: Int,
        overlapSize: Int
    ): List<Chunk> {
        // Split by paragraphs (double newlines)
        val paragraphs = content.split("\n\n")
        
        return createChunksFromParagraphs(paragraphs, tokenizer, maxChunkSize, overlapSize)
    }
    
    /**
     * Create chunks from paragraphs with overlap
     */
    private fun createChunksFromParagraphs(
        paragraphs: List<String>,
        tokenizer: com.jervis.module.indexer.tokenizer.Tokenizer,
        maxChunkSize: Int,
        overlapSize: Int,
        baseMetadata: Map<String, Any> = emptyMap()
    ): List<Chunk> {
        val chunks = mutableListOf<Chunk>()
        var currentChunk = StringBuilder()
        var currentTokenCount = 0
        var chunkIndex = 0
        
        for (paragraph in paragraphs) {
            if (paragraph.isBlank()) continue
            
            val paragraphTokenCount = tokenizer.countTokens(paragraph)
            
            // If adding this paragraph would exceed the max chunk size, create a new chunk
            if (currentTokenCount + paragraphTokenCount > maxChunkSize && currentChunk.isNotEmpty()) {
                chunks.add(Chunk(
                    content = currentChunk.toString().trim(),
                    metadata = baseMetadata + mapOf(
                        "chunk_index" to chunkIndex
                    )
                ))
                
                // Start a new chunk with overlap
                val overlapContent = getOverlapContent(currentChunk.toString(), tokenizer, overlapSize)
                currentChunk = StringBuilder(overlapContent)
                currentTokenCount = tokenizer.countTokens(overlapContent)
                chunkIndex++
            }
            
            // Add paragraph to current chunk
            currentChunk.append(paragraph).append("\n\n")
            currentTokenCount += paragraphTokenCount + 2 // +2 for the newlines
        }
        
        // Add the last chunk if not empty
        if (currentChunk.isNotEmpty()) {
            chunks.add(Chunk(
                content = currentChunk.toString().trim(),
                metadata = baseMetadata + mapOf(
                    "chunk_index" to chunkIndex
                )
            ))
        }
        
        return chunks
    }
    
    /**
     * Get overlap content from the end of a chunk
     */
    private fun getOverlapContent(
        content: String,
        tokenizer: com.jervis.module.indexer.tokenizer.Tokenizer,
        overlapSize: Int
    ): String {
        if (content.isBlank() || overlapSize <= 0) return ""
        
        // Try to get overlap by sentences first
        val sentences = content.split(Regex("(?<=[.!?])\\s+"))
        
        if (sentences.size <= 1) {
            // If no sentence boundaries, just take the last N tokens
            val tokens = tokenizer.tokenize(content)
            if (tokens.size <= overlapSize) return content
            
            val overlapTokens = tokens.takeLast(overlapSize)
            return overlapTokens.joinToString(" ")
        }
        
        // Build overlap from sentences
        val overlapBuilder = StringBuilder()
        var overlapTokenCount = 0
        
        for (i in sentences.size - 1 downTo 0) {
            val sentence = sentences[i]
            val sentenceTokenCount = tokenizer.countTokens(sentence)
            
            if (overlapTokenCount + sentenceTokenCount <= overlapSize || overlapBuilder.isEmpty()) {
                overlapBuilder.insert(0, sentence + " ")
                overlapTokenCount += sentenceTokenCount
            } else {
                break
            }
        }
        
        return overlapBuilder.toString().trim()
    }
}