package com.jervis.ocr

import com.jervis.common.dto.TikaMetadata
import com.jervis.common.dto.TikaProcessRequest
import com.jervis.common.dto.TikaProcessResult
import com.jervis.common.dto.TikaSourceLocation
import com.jervis.ocr.configuration.TikaProperties
import com.jervis.ocr.service.TikaDocumentProcessor
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.rpc.krpc.ktor.server.*
import kotlinx.rpc.krpc.serialization.cbor.cbor
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
            install(WebSockets)
            install(ContentNegotiation) {
                json(
                    Json {
                        ignoreUnknownKeys = true
                        isLenient = true
                        explicitNulls = false
                    },
                )
            }
            routing {
                rpc("/rpc") {
                    rpcConfig {
                        serialization {
                            cbor()
                        }
                    }

                    registerService<com.jervis.common.client.ITikaClient> {
                        object : com.jervis.common.client.ITikaClient {
                            override suspend fun process(request: TikaProcessRequest): TikaProcessResult {
                                val sourceInfo =
                                    when (val source = request.source) {
                                        is TikaProcessRequest.Source.FilePath -> "FilePath: ${source.path}"
                                        is TikaProcessRequest.Source.FileBytes -> "FileBytes: fileName=${source.fileName}, dataSize=${source.dataBase64.length}"
                                    }
                                logger.info { "Received Tika RPC process request: source=$sourceInfo" }

                                return try {
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
                                } catch (e: Exception) {
                                    logger.error(e) { "Tika RPC processing failed: ${e.message}" }
                                    TikaProcessResult("", null, false, e.message)
                                }
                            }
                        }
                    }
                }

                post("/api/tika/process") {
                    try {
                        val request = call.receive<TikaProcessRequest>()
                        val sourceInfo =
                            when (val source = request.source) {
                                is TikaProcessRequest.Source.FilePath -> "FilePath: ${source.path}"
                                is TikaProcessRequest.Source.FileBytes -> "FileBytes: fileName=${source.fileName}, dataSize=${source.dataBase64.length}"
                            }
                        logger.info { "Received Tika process request: source=$sourceInfo" }

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

                get("/health") {
                    call.respond(mapOf("status" to "UP"))
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
