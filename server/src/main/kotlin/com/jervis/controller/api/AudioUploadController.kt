package com.jervis.controller.api

import com.jervis.service.IAudioUploadService
import com.jervis.service.indexing.AudioUploadService
import kotlinx.coroutines.flow.Flow
import mu.KotlinLogging
import org.bson.types.ObjectId
import org.springframework.http.HttpStatus
import org.springframework.http.codec.multipart.FilePart
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

@RestController
class AudioUploadController(
    private val audioUploadService: AudioUploadService,
) : IAudioUploadService {
    private val logger = KotlinLogging.logger {}

    override suspend fun uploadProjectAudio(
        @PathVariable projectId: String,
        @RequestPart("file") filePart: FilePart,
    ): Map<String, Any> =
        try {
            val result =
                audioUploadService.uploadProjectAudio(
                    projectId = ObjectId(projectId),
                    filePart = filePart,
                )
            mapOf(
                "success" to true,
                "message" to "Audio file uploaded and queued for transcription",
                "fileName" to result.fileName,
                "fileSize" to result.fileSize,
            )
        } catch (e: Exception) {
            logger.error(e) { "Failed to upload audio for project $projectId" }
            throw ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.message, e)
        }

    override suspend fun uploadClientAudio(
        @PathVariable clientId: String,
        @RequestPart("file") filePart: FilePart,
    ): Map<String, Any> =
        try {
            val result =
                audioUploadService.uploadClientAudio(
                    clientId = ObjectId(clientId),
                    filePart = filePart,
                )
            mapOf(
                "success" to true,
                "message" to "Audio file uploaded and queued for transcription",
                "fileName" to result.fileName,
                "fileSize" to result.fileSize,
            )
        } catch (e: Exception) {
            logger.error(e) { "Failed to upload audio for client $clientId" }
            throw ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.message, e)
        }

    override suspend fun streamProjectAudio(
        @PathVariable projectId: String,
        @RequestHeader("X-File-Name") fileName: String,
        @RequestBody audioData: Flow<ByteArray>,
    ): Map<String, Any> =
        try {
            val result =
                audioUploadService.streamProjectAudio(
                    projectId = ObjectId(projectId),
                    fileName = fileName,
                    audioData = audioData,
                )
            mapOf(
                "success" to true,
                "message" to "Audio stream uploaded and queued for transcription",
                "fileName" to result.fileName,
                "fileSize" to result.fileSize,
            )
        } catch (e: Exception) {
            logger.error(e) { "Failed to stream audio for project $projectId" }
            throw ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.message, e)
        }
}
