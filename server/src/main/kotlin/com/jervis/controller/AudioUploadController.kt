package com.jervis.controller

import com.jervis.service.indexing.AudioUploadService
import kotlinx.coroutines.flow.Flow
import mu.KotlinLogging
import org.bson.types.ObjectId
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.codec.multipart.FilePart
import org.springframework.web.bind.annotation.CrossOrigin
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

@RestController
@RequestMapping("/api/v0/audio")
@CrossOrigin(origins = ["*"], allowedHeaders = ["*"])
class AudioUploadController(
    private val audioUploadService: AudioUploadService,
) {
    private val logger = KotlinLogging.logger {}

    data class UploadResponse(
        val success: Boolean,
        val message: String,
        val fileName: String,
        val fileSize: Long,
    )

    /**
     * Upload audio file for a project.
     */
    @PostMapping(
        "/project/{projectId}/upload",
        consumes = [MediaType.MULTIPART_FORM_DATA_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    suspend fun uploadProjectAudio(
        @PathVariable projectId: String,
        @RequestPart("file") filePart: FilePart,
    ): UploadResponse =
        try {
            val result =
                audioUploadService.uploadProjectAudio(
                    projectId = ObjectId(projectId),
                    filePart = filePart,
                )
            UploadResponse(
                success = true,
                message = "Audio file uploaded and queued for transcription",
                fileName = result.fileName,
                fileSize = result.fileSize,
            )
        } catch (e: Exception) {
            logger.error(e) { "Failed to upload audio for project ${'$'}projectId" }
            throw ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.message, e)
        }

    /**
     * Upload audio file for a client.
     */
    @PostMapping(
        "/client/{clientId}/upload",
        consumes = [MediaType.MULTIPART_FORM_DATA_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    suspend fun uploadClientAudio(
        @PathVariable clientId: String,
        @RequestPart("file") filePart: FilePart,
    ): UploadResponse =
        try {
            val result =
                audioUploadService.uploadClientAudio(
                    clientId = ObjectId(clientId),
                    filePart = filePart,
                )
            UploadResponse(
                success = true,
                message = "Audio file uploaded and queued for transcription",
                fileName = result.fileName,
                fileSize = result.fileSize,
            )
        } catch (e: Exception) {
            logger.error(e) { "Failed to upload audio for client ${'$'}clientId" }
            throw ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.message, e)
        }

    /**
     * Stream audio upload for a project (chunked upload).
     */
    @PostMapping(
        "/project/{projectId}/stream",
        consumes = [MediaType.APPLICATION_OCTET_STREAM_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    suspend fun streamProjectAudio(
        @PathVariable projectId: String,
        @RequestHeader("X-File-Name") fileName: String,
        @RequestBody audioData: Flow<ByteArray>,
    ): UploadResponse =
        try {
            val result =
                audioUploadService.streamProjectAudio(
                    projectId = ObjectId(projectId),
                    fileName = fileName,
                    audioData = audioData,
                )
            UploadResponse(
                success = true,
                message = "Audio stream uploaded and queued for transcription",
                fileName = result.fileName,
                fileSize = result.fileSize,
            )
        } catch (e: Exception) {
            logger.error(e) { "Failed to stream audio for project ${'$'}projectId" }
            throw ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.message, e)
        }
}
