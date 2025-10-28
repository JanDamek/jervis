package com.jervis.service.document

import com.jervis.configuration.TikaOcrProperties
import mu.KotlinLogging
import org.apache.tika.exception.TikaException
import org.apache.tika.metadata.Metadata
import org.apache.tika.parser.AutoDetectParser
import org.apache.tika.parser.ParseContext
import org.apache.tika.parser.ocr.TesseractOCRConfig
import org.apache.tika.parser.pdf.PDFParserConfig
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
    private val ocr: TikaOcrProperties,
) {
    private val logger = KotlinLogging.logger {}
    private val parser = AutoDetectParser()
    private val parseContext: ParseContext by lazy { buildParseContext() }

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
    ): DocumentProcessingResult {
        // Check if input stream is empty before passing to Tika
        if (inputStream.available() == 0) {
            logger.debug { "Empty input stream for document: $fileName, returning empty result" }
            return DocumentProcessingResult(
                plainText = "",
                metadata = DocumentMetadata(),
                success = true,
                errorMessage = "Empty input stream",
            )
        }

        return try {
            val metadata = Metadata()
            metadata["resourceName"] = fileName

            val handler = BodyContentHandler(-1) // No limit on content length

            // Use OCR-enabled ParseContext when configured
            val context = parseContext
            parser.parse(inputStream, handler, metadata, context)

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
    }

    private fun buildParseContext(): ParseContext {
        val ctx = ParseContext()

        val tess = createTesseractConfig()
        val pdf = createPdfConfig()

        ctx.set(TesseractOCRConfig::class.java, tess)
        ctx.set(PDFParserConfig::class.java, pdf)
        return ctx
    }

    private fun createTesseractConfig(): TesseractOCRConfig {
        val tess = TesseractOCRConfig()
        runCatching {
            val timeoutMs = ocr.timeoutMs
            setTesseractTimeout(tess, timeoutMs)
        }.onFailure { e ->
            logger.warn(e) { "Failed to configure TesseractOCRConfig; proceeding with defaults" }
        }
        return tess
    }

    private fun setTesseractTimeout(
        tess: TesseractOCRConfig,
        timeoutMs: Long,
    ) {
        runCatching {
            val m = TesseractOCRConfig::class.java.getMethod("setTimeoutMillis", java.lang.Long.TYPE)
            m.invoke(tess, timeoutMs)
        }.recoverCatching {
            val m = TesseractOCRConfig::class.java.getMethod("setTimeout", Integer.TYPE)
            m.invoke(tess, timeoutMs.toInt())
        }
    }

    private fun createPdfConfig(): PDFParserConfig {
        val pdf = PDFParserConfig()
        runCatching {
            val enumClass = Class.forName("org.apache.tika.parser.pdf.PDFParserConfig\$OCR_STRATEGY")
            val ocrAndText = java.lang.Enum.valueOf(enumClass as Class<out Enum<*>>, "OCR_AND_TEXT")
            val setOcrMethod =
                PDFParserConfig::class.java.methods.firstOrNull {
                    it.name == "setOcrStrategy" && it.parameterTypes.size == 1
                }
            setOcrMethod?.invoke(pdf, ocrAndText)
        }.onFailure { e ->
            logger.debug { "PDFParserConfig OCR_STRATEGY not set explicitly: ${e.message}" }
        }
        return pdf
    }

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
}
