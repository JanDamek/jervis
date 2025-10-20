package com.jervis.service

import kotlinx.coroutines.flow.Flow
import org.springframework.http.codec.multipart.FilePart
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.service.annotation.HttpExchange
import org.springframework.web.service.annotation.PostExchange

/**
 * HTTP Exchange interface for audio upload operations.
 * Provides endpoints for uploading audio files for transcription.
 */
@HttpExchange("/api/v0/audio")
interface IAudioUploadService {
    /**
     * Upload audio file for a project.
     *
     * @param projectId The project ID
     * @param filePart Multipart file upload
     * @return Upload response with success status and metadata
     */
    @PostExchange("/project/{projectId}/upload")
    suspend fun uploadProjectAudio(
        @PathVariable projectId: String,
        @RequestPart("file") filePart: FilePart,
    ): Map<String, Any>

    /**
     * Upload audio file for a client.
     *
     * @param clientId The client ID
     * @param filePart Multipart file upload
     * @return Upload response with success status and metadata
     */
    @PostExchange("/client/{clientId}/upload")
    suspend fun uploadClientAudio(
        @PathVariable clientId: String,
        @RequestPart("file") filePart: FilePart,
    ): Map<String, Any>

    /**
     * Stream audio upload for a project (chunked upload).
     *
     * @param projectId The project ID
     * @param fileName File name header
     * @param audioData Audio data stream
     * @return Upload response with success status and metadata
     */
    @PostExchange("/project/{projectId}/stream")
    suspend fun streamProjectAudio(
        @PathVariable projectId: String,
        @RequestHeader("X-File-Name") fileName: String,
        @RequestBody audioData: Flow<ByteArray>,
    ): Map<String, Any>
}
