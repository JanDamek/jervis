package com.jervis.ocr.service

import com.jervis.ocr.configuration.TikaProperties
import mu.KotlinLogging
import org.apache.tika.exception.TikaException
import org.apache.tika.metadata.Metadata
import org.apache.tika.parser.AutoDetectParser
import org.apache.tika.parser.ParseContext
import org.apache.tika.parser.ocr.TesseractOCRConfig
import org.apache.tika.parser.pdf.PDFParserConfig
import org.apache.tika.sax.BodyContentHandler
import java.io.BufferedInputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.pathString

/**
 * Service for processing various document formats using Apache Tika.
 * Provides text extraction with metadata preservation for source location tracking.
 */
class TikaDocumentProcessor(
    private val ocr: TikaProperties,
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
        val checkedStream = if (inputStream.markSupported()) inputStream else BufferedInputStream(inputStream)
        checkedStream.mark(1)
        val firstByte = checkedStream.read()
        if (firstByte == -1) {
            runCatching { checkedStream.reset() }
            logger.debug { "Empty input stream for document: $fileName, returning empty result" }
            return DocumentProcessingResult(
                plainText = "",
                metadata = DocumentMetadata(),
                success = true,
                errorMessage = "Empty input stream",
            )
        }
        checkedStream.reset()

        return try {
            val metadata = Metadata()
            metadata["resourceName"] = fileName

            val handler = LinkPreservingContentHandler()

            val context = parseContext
            parser.parse(checkedStream, handler, metadata, context)

            val extractedText = handler.toString()
            val documentMetadata = extractMetadata(metadata, fileName, sourceLocation)

            logger.debug {
                "Successfully processed document: $fileName, extracted ${extractedText.length} characters"
            }

            DocumentProcessingResult(
                plainText = extractedText,
                metadata = documentMetadata,
                success = true,
            )
        } catch (e: TikaException) {
            logger.error { "Tika parsing error for document: $fileName - ${e.message}" }
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
        // Configure timeout: prefer env var TIKA_OCR_TIMEOUT_MS, fallback to properties
        runCatching {
            val envTimeout = System.getenv("TIKA_OCR_TIMEOUT_MS")?.toLongOrNull()
            val timeoutMs = envTimeout ?: ocr.timeoutMs
            setTesseractTimeout(tess, timeoutMs)
        }.onFailure { e ->
            logger.warn(e) { "Failed to configure Tesseract timeout; proceeding with defaults" }
        }
        // Configure language if provided via env var
        runCatching {
            val lang = System.getenv("TIKA_OCR_LANG")?.takeIf { it.isNotBlank() }
            if (lang != null) {
                val m = TesseractOCRConfig::class.java.getMethod("setLanguage", String::class.java)
                m.invoke(tess, lang)
            }
        }.onFailure { e ->
            logger.debug { "Tesseract language not set explicitly: ${e.message}" }
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
            val constants = enumClass.enumConstants?.filterIsInstance<Enum<*>>() ?: emptyList()
            val ocrAndText = constants.firstOrNull { it.name == "OCR_AND_TEXT" }
            val setOcrMethod =
                PDFParserConfig::class.java.methods.firstOrNull {
                    it.name == "setOcrStrategy" && it.parameterTypes.size == 1
                }
            if (ocrAndText != null && setOcrMethod != null) {
                setOcrMethod.invoke(pdf, ocrAndText)
            }
        }.onFailure { e ->
            logger.debug { "PDFParserConfig OCR_STRATEGY not set explicitly: ${e.message}" }
        }
        return pdf
    }

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

    private fun isOcrImageFile(fileName: String): Boolean {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return ext in setOf("png", "jpg", "jpeg", "bmp", "tif", "tiff", "gif", "webp", "heic", "heif")
    }

    private fun guessMimeTypeFromFileName(fileName: String): String =
        when (fileName.substringAfterLast('.', "").lowercase()) {
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "bmp" -> "image/bmp"
            "tif", "tiff" -> "image/tiff"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "heic", "heif" -> "image/heic"
            "pdf" -> "application/pdf"
            else -> "application/octet-stream"
        }

    private fun isPdfFile(fileName: String): Boolean = fileName.substringAfterLast('.', "").equals("pdf", ignoreCase = true)
}

/**
 * Custom ContentHandler that preserves link URLs inline within the text.
 * For <a href="URL">text</a>, outputs: "text (URL)"
 */
private class LinkPreservingContentHandler : BodyContentHandler(-1) {
    private val currentLink = ThreadLocal<String?>()

    override fun startElement(
        uri: String?,
        localName: String?,
        qName: String?,
        atts: org.xml.sax.Attributes?,
    ) {
        super.startElement(uri, localName, qName, atts)
        if (localName == "a" && atts != null) {
            val href = atts.getValue("href")
            if (!href.isNullOrBlank()) {
                currentLink.set(href)
            }
        }
    }

    override fun endElement(
        uri: String?,
        localName: String?,
        qName: String?,
    ) {
        if (localName == "a") {
            val href = currentLink.get()
            if (!href.isNullOrBlank()) {
                characters(" ($href)".toCharArray(), 0, href.length + 3)
                currentLink.remove()
            }
        }
        super.endElement(uri, localName, qName)
    }
}
