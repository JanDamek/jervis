package com.jervis.service.document

import com.jervis.configuration.prompts.PromptTypeEnum
import com.jervis.service.gateway.core.LlmGateway
import com.jervis.service.indexing.dto.ContentSentenceSplittingResponse
import mu.KotlinLogging
import org.apache.tika.exception.TikaException
import org.apache.tika.metadata.Metadata
import org.apache.tika.parser.AutoDetectParser
import org.apache.tika.sax.BodyContentHandler
import org.springframework.stereotype.Service
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.pathString

/**
 * Service for processing various document formats using Apache Tika.
 * Provides text extraction with metadata preservation for source location tracking.
 */
@Service
class TikaDocumentProcessor(
    private val llmGateway: LlmGateway,
) {
    private val logger = KotlinLogging.logger {}
    private val parser = AutoDetectParser()

    /**
     * Document processing result with extracted content and metadata
     */
    data class DocumentProcessingResult(
        val plainText: String,
        val metadata: DocumentMetadata,
        val success: Boolean,
        val errorMessage: String? = null,
    )

    /**
     * Document metadata extracted by Tika with location tracking information
     */
    data class DocumentMetadata(
        val title: String? = null,
        val author: String? = null,
        val creationDate: String? = null,
        val lastModified: String? = null,
        val contentType: String? = null,
        val pageCount: Int? = null,
        val language: String? = null,
        val keywords: List<String> = emptyList(),
        val customProperties: Map<String, String> = emptyMap(),
        // Location tracking for source reference
        val sourceLocation: SourceLocation? = null,
    )

    /**
     * Source location information for tracking content within documents
     */
    data class SourceLocation(
        val documentPath: String,
        val pageNumber: Int? = null,
        val paragraphIndex: Int? = null,
        val characterOffset: Int? = null,
        val sectionTitle: String? = null,
    )

    /**
     * Process a document from a file path
     */
    suspend fun processDocument(documentPath: Path): DocumentProcessingResult =
        try {
            logger.debug { "Processing document: ${documentPath.pathString}" }

            Files.newInputStream(documentPath).use { inputStream ->
                processDocumentStream(
                    inputStream = inputStream,
                    fileName = documentPath.fileName.toString(),
                    sourceLocation = SourceLocation(documentPath = documentPath.pathString),
                )
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to process document: ${documentPath.pathString}" }
            DocumentProcessingResult(
                plainText = "",
                metadata = DocumentMetadata(),
                success = false,
                errorMessage = e.message,
            )
        }

    /**
     * Process document from input stream with custom source location
     */
    suspend fun processDocumentStream(
        inputStream: InputStream,
        fileName: String,
        sourceLocation: SourceLocation? = null,
    ): DocumentProcessingResult =
        try {
            val metadata = Metadata()
            metadata["resourceName"] = fileName

            val handler = BodyContentHandler(-1) // No limit on content length
            parser.parse(inputStream, handler, metadata)

            val extractedText = handler.toString()
            val documentMetadata = extractMetadata(metadata, fileName, sourceLocation)

            logger.debug { "Successfully processed document: $fileName, extracted ${extractedText.length} characters" }

            DocumentProcessingResult(
                plainText = extractedText,
                metadata = documentMetadata,
                success = true,
            )
        } catch (e: TikaException) {
            logger.error(e) { "Tika parsing error for document: $fileName" }
            DocumentProcessingResult(
                plainText = "",
                metadata = DocumentMetadata(),
                success = false,
                errorMessage = "Tika parsing failed: ${e.message}",
            )
        } catch (e: Exception) {
            logger.error(e) { "Failed to process document stream: $fileName" }
            DocumentProcessingResult(
                plainText = "",
                metadata = DocumentMetadata(),
                success = false,
                errorMessage = e.message,
            )
        }

    /**
     * Split extracted text into sentences with location tracking using LLM-based processing.
     * Uses CONTENT_SENTENCE_SPLITTING prompt for intelligent sentence splitting optimized for RAG.
     */
    suspend fun splitIntoSentencesWithLocation(
        text: String,
        metadata: DocumentMetadata,
    ): List<SentenceWithLocation> =
        try {
            val response =
                llmGateway.callLlm(
                    type = PromptTypeEnum.CONTENT_SPLIT_SENTENCES,
                    quick = false,
                    responseSchema = ContentSentenceSplittingResponse(),
                    mappingValue =
                        mapOf(
                            "contentType" to (metadata.contentType ?: "document"),
                            "source" to (metadata.title ?: metadata.sourceLocation?.documentPath ?: "unknown"),
                            "content" to text,
                        ),
                )

            // Map LLM sentences to SentenceWithLocation objects with metadata
            response.sentences
                .mapIndexed { index, sentence ->
                    SentenceWithLocation(
                        text = sentence,
                        location =
                            SourceLocation(
                                documentPath = metadata.sourceLocation?.documentPath ?: "unknown",
                                paragraphIndex = index,
                                characterOffset = calculateCharacterOffset(text, sentence),
                                sectionTitle = extractNearestSectionTitle(text, sentence),
                                pageNumber = metadata.sourceLocation?.pageNumber,
                            ),
                    )
                }.filter { it.text.trim().isNotEmpty() && it.text.length >= 10 }
        } catch (e: Exception) {
            logger.error(e) { "Failed to process content with LLM, falling back to basic sentence splitting" }

            // Fallback to basic processing if LLM fails
            val sentences =
                text
                    .split(Regex("[.!?\\n]+"))
                    .map { it.trim() }
                    .filter { it.length > 15 && !it.matches(Regex("^[\\s\\p{Punct}]*$")) }

            sentences.mapIndexed { index, sentence ->
                SentenceWithLocation(
                    text = sentence,
                    location =
                        SourceLocation(
                            documentPath = metadata.sourceLocation?.documentPath ?: "unknown",
                            paragraphIndex = index,
                            characterOffset = calculateCharacterOffset(text, sentence),
                            sectionTitle = extractNearestSectionTitle(text, sentence),
                            pageNumber = metadata.sourceLocation?.pageNumber,
                        ),
                )
            }
        }

    /**
     * Sentence with its location within the document
     */
    data class SentenceWithLocation(
        val text: String,
        val location: SourceLocation,
    )

    /**
     * Extract metadata from Tika Metadata object
     */
    private fun extractMetadata(
        tikaMetadata: Metadata,
        fileName: String,
        sourceLocation: SourceLocation?,
    ): DocumentMetadata {
        val keywords =
            tikaMetadata["keywords"]
                ?.split(",", ";")
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?: emptyList()

        val customProperties = mutableMapOf<String, String>()
        tikaMetadata.names().forEach { name ->
            val value = tikaMetadata[name]
            if (value != null && !isStandardMetadata(name)) {
                customProperties[name] = value
            }
        }

        return DocumentMetadata(
            title = tikaMetadata["title"] ?: extractTitleFromFileName(fileName),
            author = tikaMetadata["author"],
            creationDate = tikaMetadata["Creation-Date"],
            lastModified = tikaMetadata["Last-Modified"],
            contentType = tikaMetadata["Content-Type"],
            pageCount = tikaMetadata["xmpTPg:NPages"]?.toIntOrNull(),
            language = tikaMetadata["language"],
            keywords = keywords,
            customProperties = customProperties,
            sourceLocation = sourceLocation,
        )
    }

    /**
     * Check if the metadata name is a standard Tika metadata field
     */
    private fun isStandardMetadata(name: String): Boolean =
        listOf(
            "title",
            "author",
            "Creation-Date",
            "Last-Modified",
            "Content-Type",
            "language",
            "keywords",
            "xmpTPg:NPages",
            "resourceName",
        ).contains(name)

    /**
     * Extract title from filename if no title metadata is available
     */
    private fun extractTitleFromFileName(fileName: String): String = fileName.substringBeforeLast('.').replace("[_-]".toRegex(), " ").trim()

    /**
     * Calculate approximate character offset for a sentence in the text
     */
    private fun calculateCharacterOffset(
        fullText: String,
        sentence: String,
    ): Int = fullText.indexOf(sentence).takeIf { it >= 0 } ?: 0

    /**
     * Extract the nearest section title for context (simplified implementation)
     */
    private fun extractNearestSectionTitle(
        fullText: String,
        sentence: String,
    ): String? {
        val sentenceIndex = fullText.indexOf(sentence)
        if (sentenceIndex < 0) return null

        // Look for headers/titles in the 1000 characters before the sentence
        val contextStart = maxOf(0, sentenceIndex - 1000)
        val context = fullText.substring(contextStart, sentenceIndex)

        // Simple regex to find potential section titles (lines that start with caps or are short)
        val titlePattern = Regex("^([A-Z][^.!?\\n]{5,50})$", RegexOption.MULTILINE)
        val matches = titlePattern.findAll(context).toList()

        return matches
            .lastOrNull()
            ?.groupValues
            ?.get(1)
            ?.trim()
    }
}
