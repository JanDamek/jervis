package com.jervis.ocr.api

import com.jervis.common.client.ITikaClient
import com.jervis.common.dto.TikaMetadata
import com.jervis.common.dto.TikaProcessRequest
import com.jervis.common.dto.TikaProcessResult
import com.jervis.common.dto.TikaSourceLocation
import com.jervis.ocr.service.TikaDocumentProcessor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import java.nio.file.Path
import java.util.Base64

@RestController
class TikaController(
    private val tikaProcessor: TikaDocumentProcessor,
) : ITikaClient {
    private val logger = KotlinLogging.logger {}

    override suspend fun process(
        @RequestBody request: TikaProcessRequest,
    ): TikaProcessResult =
        withContext(Dispatchers.IO) {
            val sourceInfo =
                when (val source = request.source) {
                    is TikaProcessRequest.Source.FilePath -> "FilePath: ${source.path}"
                    is TikaProcessRequest.Source.FileBytes -> "FileBytes: fileName=${source.fileName}, dataSize=${source.dataBase64.length}"
                }
            logger.info { "Received Tika process request: source=$sourceInfo, includeMetadata=${request.includeMetadata}" }

            try {
                val result =
                    when (val source = request.source) {
                        is TikaProcessRequest.Source.FilePath -> {
                            val path = Path.of(source.path)
                            val res = tikaProcessor.processDocument(path)
                            convertToDto(res, request.includeMetadata)
                        }

                        is TikaProcessRequest.Source.FileBytes -> {
                            val bytes = Base64.getDecoder().decode(source.dataBase64)
                            val inputStream = bytes.inputStream()
                            val sourceLocation =
                                TikaDocumentProcessor.SourceLocation(
                                    documentPath = source.fileName,
                                )
                            val res =
                                tikaProcessor.processDocumentStream(
                                    inputStream = inputStream,
                                    fileName = source.fileName,
                                    sourceLocation = sourceLocation,
                                )
                            convertToDto(res, request.includeMetadata)
                        }
                    }
                logger.info {
                    "Tika processing completed: success=${result.success}, textLength=${result.plainText.length}, hasMetadata=${result.metadata != null}"
                }
                result
            } catch (e: Exception) {
                logger.error(e) { "Tika processing failed: ${e.message}" }
                throw e
            }
        }

    private fun convertToDto(
        result: TikaDocumentProcessor.DocumentProcessingResult,
        includeMetadata: Boolean,
    ): TikaProcessResult {
        val metadata =
            if (includeMetadata && result.metadata != null) {
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
