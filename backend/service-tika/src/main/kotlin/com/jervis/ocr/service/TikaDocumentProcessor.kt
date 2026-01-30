package com.jervis.ocr.service

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import org.apache.tika.exception.TikaException
import org.apache.tika.io.TemporaryResources
import org.apache.tika.io.TikaInputStream
import org.apache.tika.metadata.HttpHeaders
import org.apache.tika.metadata.Metadata
import org.apache.tika.metadata.TikaCoreProperties
import org.apache.tika.mime.MediaType
import org.apache.tika.parser.AutoDetectParser
import org.apache.tika.parser.ParseContext
import org.apache.tika.parser.Parser
import org.apache.tika.parser.html.JSoupParser
import org.apache.tika.parser.ocr.TesseractOCRConfig
import org.apache.tika.parser.pdf.PDFParserConfig
import org.apache.tika.parser.csv.TextAndCSVParser
import org.apache.tika.sax.BodyContentHandler
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.pathString

/**
 * Service for processing various document formats using Apache Tika.
 * Provides text extraction with metadata preservation for source location tracking.
 */
class TikaDocumentProcessor(
    private val timeoutMs: Long,
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

            withContext(Dispatchers.IO) {
                val sourceSize = runCatching { Files.size(documentPath) }.getOrNull()
                processDocumentWithSource(
                    fileName = documentPath.fileName.toString(),
                    sourceLocation = SourceLocation(documentPath = documentPath.pathString),
                    sourceSize = sourceSize,
                    streamSource = StreamSource { Files.newInputStream(documentPath) },
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
    fun processDocumentStream(
        inputStream: InputStream,
        fileName: String,
        sourceLocation: SourceLocation? = null,
    ): DocumentProcessingResult {
        val bytes = inputStream.use { it.readBytes() }
        return processDocumentWithSource(
            fileName = fileName,
            sourceLocation = sourceLocation,
            sourceSize = bytes.size.toLong(),
            streamSource = StreamSource { ByteArrayInputStream(bytes) },
        )
    }

    private fun processDocumentWithSource(
        fileName: String,
        sourceLocation: SourceLocation?,
        sourceSize: Long?,
        streamSource: StreamSource,
    ): DocumentProcessingResult {
        val metadata = Metadata()
        metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, fileName)

        return try {
            val context = parseContext

            val detectedType = detectMediaType(streamSource, metadata)
            if (detectedType != null) {
                metadata.set(HttpHeaders.CONTENT_TYPE, detectedType.toString())
            }

            val probe = probeStream(streamSource)
            logger.debug {
                "Tika probe: fileName=$fileName size=${sourceSize ?: "unknown"} bytesRead=${probe.bytesRead} " +
                    "detectedType=${detectedType?.toString() ?: "unknown"} looksLikeText=${probe.looksLikeText} " +
                    "looksLikeMarkup=${probe.looksLikeMarkup} printableRatio=${probe.printableRatio} " +
                    "preview=${probe.preview}"
            }

            var extractedText = parseWith(parser, streamSource, metadata, context)
            logger.debug { "Tika parse result: parser=AutoDetectParser chars=${extractedText.length}" }

            if (extractedText.isBlank() && isHtmlType(detectedType, fileName)) {
                logger.debug { "AutoDetectParser returned empty text for HTML, trying JSoupParser explicitly" }
                extractedText = parseWith(JSoupParser(), streamSource, metadata, context)
                logger.debug { "Tika parse result: parser=JSoupParser chars=${extractedText.length}" }
            }

            if (extractedText.isBlank() && shouldTryTextFallback(detectedType, fileName, probe)) {
                logger.debug { "AutoDetectParser returned empty text, trying TextAndCSVParser fallback" }
                extractedText = parseWith(TextAndCSVParser(), streamSource, metadata, context)
                logger.debug { "Tika parse result: parser=TextAndCSVParser chars=${extractedText.length}" }
            }

            if (extractedText.isBlank() && shouldReturnRawText(detectedType, fileName, probe)) {
                logger.debug { "Text parser returned empty text, falling back to raw text" }
                extractedText = readRawText(streamSource)
                logger.debug { "Tika parse result: parser=RawText chars=${extractedText.length}" }
            }

            val documentMetadata = extractMetadata(metadata, fileName, sourceLocation)

            logger.debug {
                val contentType = documentMetadata.contentType ?: detectedType?.toString() ?: "unknown"
                "Successfully processed document: $fileName, contentType=$contentType, " +
                    "extracted ${extractedText.length} characters"
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
        var ocrEnabled = true
        var configuredLang: String? = null
        // Configure timeout: prefer env var TIKA_OCR_TIMEOUT_MS, fallback to properties
        runCatching {
            setTesseractTimeout(tess, timeoutMs)
        }.onFailure { e ->
            logger.warn(e) { "Failed to configure Tesseract timeout; proceeding with defaults" }
        }
        runCatching {
            val enabled =
                System.getenv("TIKA_OCR_ENABLED")
                    ?.trim()
                    ?.let { value ->
                        value.equals("true", ignoreCase = true) || value == "1" || value.equals("yes", ignoreCase = true)
                    } ?: true
            ocrEnabled = enabled
            tess.setSkipOcr(!enabled)
        }.onFailure { e ->
            logger.debug { "Tesseract OCR enable flag not set explicitly: ${e.message}" }
        }
        // Configure language if provided via env var
        runCatching {
            val lang = System.getenv("TIKA_OCR_LANG")?.takeIf { it.isNotBlank() }
            if (lang != null) {
                configuredLang = lang
                val m = TesseractOCRConfig::class.java.getMethod("setLanguage", String::class.java)
                m.invoke(tess, lang)
            }
        }.onFailure { e ->
            logger.debug { "Tesseract language not set explicitly: ${e.message}" }
        }
        runCatching {
            tess.setOutputType("txt")
        }.onFailure { e ->
            logger.debug { "Tesseract output type not set explicitly: ${e.message}" }
        }
        logger.debug {
            "Tesseract OCR config: enabled=$ocrEnabled lang=${configuredLang ?: "default"} timeoutMs=$timeoutMs outputType=txt"
        }
        return tess
    }

    private fun setTesseractTimeout(
        tess: TesseractOCRConfig,
        timeoutMs: Long,
    ) {
        val timeoutSeconds = (timeoutMs / 1000).toInt().coerceAtLeast(1)
        runCatching {
            val m = TesseractOCRConfig::class.java.getMethod("setTimeoutMillis", java.lang.Long.TYPE)
            m.invoke(tess, timeoutMs)
        }.recoverCatching {
            val m = TesseractOCRConfig::class.java.getMethod("setTimeout", Integer.TYPE)
            m.invoke(tess, timeoutMs.toInt())
        }.recoverCatching {
            val m = TesseractOCRConfig::class.java.getMethod("setTimeoutSeconds", Integer.TYPE)
            m.invoke(tess, timeoutSeconds)
        }.getOrThrow()
    }

    private fun createPdfConfig(): PDFParserConfig {
        val pdf = PDFParserConfig()
        runCatching {
            val enumClass = Class.forName($$"org.apache.tika.parser.pdf.PDFParserConfig$OCR_STRATEGY")
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
        runCatching {
            pdf.setExtractInlineImages(true)
            pdf.setExtractInlineImageMetadataOnly(false)
        }.onFailure { e ->
            logger.debug { "PDFParserConfig inline image extraction not set explicitly: ${e.message}" }
        }
        return pdf
    }

    private fun interface StreamSource {
        fun open(): InputStream
    }

    private inline fun <T> withTikaStream(
        streamSource: StreamSource,
        metadata: Metadata,
        block: (TikaInputStream) -> T,
    ): T =
        TikaInputStream.get(streamSource.open(), TemporaryResources(), metadata).use { stream ->
            block(stream)
        }

    private fun detectMediaType(
        streamSource: StreamSource,
        metadata: Metadata,
    ): MediaType? =
        runCatching {
            withTikaStream(streamSource, metadata) { stream ->
                parser.detector.detect(stream, metadata)
            }
        }.onFailure { e ->
            logger.debug { "Tika media type detection failed: ${e.message}" }
        }.getOrNull()

    private fun parseWith(
        parser: Parser,
        streamSource: StreamSource,
        metadata: Metadata,
        context: ParseContext,
    ): String {
        return withTikaStream(streamSource, metadata) { stream ->
            val handler = LinkPreservingContentHandler()
            parser.parse(stream, handler, metadata, context)
            handler.toString()
        }
    }

    private fun shouldTryTextFallback(
        mediaType: MediaType?,
        fileName: String,
        probe: StreamProbe,
    ): Boolean = isTextualType(mediaType, fileName) || probe.looksLikeText

    private fun shouldReturnRawText(
        mediaType: MediaType?,
        fileName: String,
        probe: StreamProbe,
    ): Boolean {
        if (probe.looksLikeText && !probe.looksLikeMarkup) {
            return true
        }
        if (isMarkupType(mediaType, fileName) || probe.looksLikeMarkup) {
            return false
        }
        return isPlainTextType(mediaType, fileName)
    }

    private fun isHtmlType(mediaType: MediaType?, fileName: String): Boolean {
        val baseType = mediaType?.baseType?.toString()?.lowercase()
        val subtype = mediaType?.subtype?.lowercase()
        if (baseType == "text/html" || baseType == "application/xhtml+xml") {
            return true
        }
        if (subtype != null && subtype.contains("html")) {
            return true
        }
        val lower = fileName.lowercase()
        return lower.endsWith(".html") || lower.endsWith(".htm") || lower.endsWith(".xhtml")
    }

    private fun isMarkupType(mediaType: MediaType?, fileName: String): Boolean {
        val baseType = mediaType?.baseType?.toString()?.lowercase()
        val subtype = mediaType?.subtype?.lowercase()
        if (isHtmlType(mediaType, fileName)) {
            return true
        }
        if (baseType == "application/xml" || baseType == "text/xml") {
            return true
        }
        if (subtype != null && subtype.endsWith("+xml")) {
            return true
        }
        val lower = fileName.lowercase()
        return lower.endsWith(".xml") || lower.endsWith(".xsl") || lower.endsWith(".xsd") || lower.endsWith(".svg")
    }

    private fun isTextualType(mediaType: MediaType?, fileName: String): Boolean {
        val baseType = mediaType?.baseType?.toString()?.lowercase()
        val subtype = mediaType?.subtype?.lowercase()
        if (baseType != null) {
            if (baseType.startsWith("text/")) {
                return true
            }
            if (baseType == "application/xml" || baseType == "application/xhtml+xml" || baseType == "application/json") {
                return true
            }
            if (baseType.endsWith("+xml") || baseType.endsWith("+json")) {
                return true
            }
        }
        if (subtype != null && subtype.endsWith("+json")) {
            return true
        }
        val lower = fileName.lowercase()
        return lower.endsWith(".txt") ||
            lower.endsWith(".md") ||
            lower.endsWith(".csv") ||
            lower.endsWith(".tsv") ||
            lower.endsWith(".log") ||
            lower.endsWith(".json") ||
            lower.endsWith(".ndjson") ||
            lower.endsWith(".yaml") ||
            lower.endsWith(".yml") ||
            lower.endsWith(".xml") ||
            lower.endsWith(".html") ||
            lower.endsWith(".htm") ||
            lower.endsWith(".xhtml")
    }

    private fun isPlainTextType(mediaType: MediaType?, fileName: String): Boolean {
        val baseType = mediaType?.baseType?.toString()?.lowercase()
        val subtype = mediaType?.subtype?.lowercase()
        if (baseType == "text/plain" || baseType == "text/csv") {
            return true
        }
        if (baseType == "application/json" || baseType == "application/x-ndjson") {
            return true
        }
        if (subtype != null && subtype.endsWith("+json")) {
            return true
        }
        val lower = fileName.lowercase()
        return lower.endsWith(".txt") ||
            lower.endsWith(".log") ||
            lower.endsWith(".md") ||
            lower.endsWith(".csv") ||
            lower.endsWith(".tsv") ||
            lower.endsWith(".json") ||
            lower.endsWith(".ndjson") ||
            lower.endsWith(".yaml") ||
            lower.endsWith(".yml") ||
            lower.endsWith(".ini") ||
            lower.endsWith(".properties")
    }

    private fun probeStream(streamSource: StreamSource): StreamProbe =
        streamSource.open().use { stream ->
            val buffer = ByteArray(4096)
            val read = stream.read(buffer)
            if (read <= 0) {
                return StreamProbe(
                    bytesRead = 0,
                    printableRatio = 0.0,
                    hasZeroBytes = false,
                    looksLikeText = false,
                    looksLikeMarkup = false,
                    preview = "",
                )
            }
            val stats = computeTextStats(buffer, read)
            val preview = buildPreview(buffer, read)
            val looksLikeMarkup = looksLikeMarkup(preview)
            val looksLikeText = !stats.hasZeroBytes && stats.printableRatio >= 0.85
            StreamProbe(
                bytesRead = read,
                printableRatio = stats.printableRatio,
                hasZeroBytes = stats.hasZeroBytes,
                looksLikeText = looksLikeText,
                looksLikeMarkup = looksLikeMarkup,
                preview = preview,
            )
        }

    private fun computeTextStats(
        buffer: ByteArray,
        length: Int,
    ): TextStats {
        var printable = 0
        var zeroBytes = 0

        for (i in 0 until length) {
            val value = buffer[i]
            if (value == 0.toByte()) {
                zeroBytes++
                continue
            }
            val unsigned = value.toInt() and 0xFF
            when {
                unsigned == 0x09 || unsigned == 0x0A || unsigned == 0x0D -> printable++
                unsigned in 0x20..0x7E || unsigned >= 0xA0 -> printable++
                else -> Unit
            }
        }

        if (zeroBytes > 0) {
            return TextStats(0.0, hasZeroBytes = true)
        }
        val ratio = printable.toDouble() / length.toDouble()
        return TextStats(ratio, hasZeroBytes = false)
    }

    private fun buildPreview(
        buffer: ByteArray,
        length: Int,
    ): String {
        val maxChars = 160
        val builder = StringBuilder()
        for (i in 0 until length) {
            if (builder.length >= maxChars) {
                builder.append("...")
                break
            }
            val value = buffer[i]
            val unsigned = value.toInt() and 0xFF
            val char =
                when (unsigned) {
                    0x09 -> "\\t"
                    0x0A -> "\\n"
                    0x0D -> "\\r"
                    in 0x20..0x7E -> unsigned.toChar().toString()
                    else -> "."
                }
            builder.append(char)
        }
        return builder.toString()
    }

    private fun looksLikeMarkup(preview: String): Boolean {
        val lower = preview.lowercase()
        if (lower.contains("<!doctype") || lower.contains("<?xml")) {
            return true
        }
        return Regex("<\\s*/?\\s*[a-zA-Z][^>]*>").containsMatchIn(lower)
    }

    private fun readRawText(streamSource: StreamSource): String =
        streamSource.open().use { stream ->
            String(stream.readBytes(), Charsets.UTF_8)
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
            TikaCoreProperties.RESOURCE_NAME_KEY,
        ).contains(name)

    /**
     * Extract title from filename if no title metadata is available
     */
    private fun extractTitleFromFileName(fileName: String): String = fileName.substringBeforeLast('.').replace("[_-]".toRegex(), " ").trim()
}

private data class StreamProbe(
    val bytesRead: Int,
    val printableRatio: Double,
    val hasZeroBytes: Boolean,
    val looksLikeText: Boolean,
    val looksLikeMarkup: Boolean,
    val preview: String,
)

private data class TextStats(
    val printableRatio: Double,
    val hasZeroBytes: Boolean,
)

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
