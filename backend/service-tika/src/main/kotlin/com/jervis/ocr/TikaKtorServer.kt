package com.jervis.ocr

import com.jervis.common.dto.TikaMetadata
import com.jervis.common.dto.TikaProcessRequest
import com.jervis.common.dto.TikaProcessResult
import com.jervis.common.dto.TikaSourceLocation
import com.jervis.ocr.configuration.TikaProperties
import com.jervis.ocr.service.TikaDocumentProcessor
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import mu.KotlinLogging
import java.nio.file.Path
import java.util.Base64

object TikaKtorServer {
    private val logger = KotlinLogging.logger {}

    @JvmStatic
    fun main(args: Array<String>) {
        val port = System.getenv("SERVER_PORT")?.toIntOrNull() ?: 3400
        val host = System.getenv("HOST") ?: "0.0.0.0"
        val timeoutMs = System.getenv("TIKA_OCR_TIMEOUT_MS")?.toLongOrNull() ?: 120000L
        logger.info { "Starting Tika Ktor server on $host:$port (timeoutMs=$timeoutMs)" }

        val properties = TikaProperties(timeoutMs)
        val processor = TikaDocumentProcessor(properties)

        embeddedServer(Netty, port = port, host = host) {
            install(ContentNegotiation) {
                json(
                    Json {
                        ignoreUnknownKeys = true
                        isLenient = true
                    },
                )
            }
            routing {
                post("/api/tika/process") {
                    val sourceInfo =
                        when (val source = call.receive<TikaProcessRequest>().source) {
                            is TikaProcessRequest.Source.FilePath -> "FilePath: ${source.path}"
                            is TikaProcessRequest.Source.FileBytes -> "FileBytes: fileName=${source.fileName}, dataSize=${source.dataBase64.length}"
                        }
                    logger.info { "Received Tika process request: source=$sourceInfo" }

                    try {
                        val request = call.receive<TikaProcessRequest>()
                        val result =
                            withContext(Dispatchers.IO) {
                                when (val source = request.source) {
                                    is TikaProcessRequest.Source.FilePath -> {
                                        val path = Path.of(source.path)
                                        processor
                                            .processDocument(path)
                                            .let { convertToDto(it, request.includeMetadata) }
                                    }

                                    is TikaProcessRequest.Source.FileBytes -> {
                                        val bytes = Base64.getDecoder().decode(source.dataBase64)
                                        val inputStream = bytes.inputStream()
                                        val sourceLocation = TikaDocumentProcessor.SourceLocation(source.fileName)
                                        processor
                                            .processDocumentStream(inputStream, source.fileName, sourceLocation)
                                            .let { convertToDto(it, request.includeMetadata) }
                                    }
                                }
                            }
                        logger.info { "Tika processing completed: success=${result.success}, textLength=${result.plainText.length}" }
                        call.respond(result)
                    } catch (e: Exception) {
                        logger.error(e) { "Tika processing failed: ${e.message}" }
                        call.respond(TikaProcessResult("", null, false, e.message))
                    }
                }
            }
        }.start(wait = true)
    }

    private fun convertToDto(
        result: TikaDocumentProcessor.DocumentProcessingResult,
        includeMetadata: Boolean,
    ): TikaProcessResult {
        val metadata =
            if (includeMetadata) {
                TikaMetadata(
                    title = result.metadata.title,
                    author = result.metadata.author,
                    creationDate = result.metadata.creationDate,
                    lastModified = result.metadata.lastModified,
                    contentType = result.metadata.contentType,
                    pageCount = result.metadata.pageCount,
                    language = result.metadata.language,
                    keywords = result.metadata.keywords,
                    customProperties = result.metadata.customProperties,
                    sourceLocation =
                        result.metadata.sourceLocation?.let { loc ->
                            TikaSourceLocation(
                                documentPath = loc.documentPath,
                                pageNumber = loc.pageNumber,
                                paragraphIndex = loc.paragraphIndex,
                                characterOffset = loc.characterOffset,
                                sectionTitle = loc.sectionTitle,
                            )
                        },
                )
            } else {
                null
            }
        return TikaProcessResult(
            plainText = result.plainText,
            metadata = metadata,
            success = result.success,
            errorMessage = result.errorMessage,
        )
    }
}
