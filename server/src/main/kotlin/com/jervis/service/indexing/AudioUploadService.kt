package com.jervis.service.indexing

import com.jervis.entity.ProjectDocument
import com.jervis.repository.mongo.ClientMongoRepository
import com.jervis.repository.mongo.ProjectMongoRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import org.bson.types.ObjectId
import org.springframework.http.codec.multipart.FilePart
import org.springframework.stereotype.Service
import java.nio.file.Path

@Service
class AudioUploadService(
    private val projectRepository: ProjectMongoRepository,
    private val clientRepository: ClientMongoRepository,
    private val audioTranscriptIndexingService: AudioTranscriptIndexingService,
) {
    private val logger = KotlinLogging.logger {}

    data class UploadResult(
        val fileName: String,
        val fileSize: Long,
        val savedPath: Path,
    )

    suspend fun uploadProjectAudio(
        projectId: ObjectId,
        filePart: FilePart,
    ): UploadResult =
        withContext(Dispatchers.IO) {
            projectRepository.findById(projectId)
                ?: error("Project not found: $projectId")

            // TODO: Audio monitoring was in project.overrides which is removed
            error("Audio monitoring configuration removed - needs redesign")
        }

    suspend fun uploadClientAudio(
        clientId: ObjectId,
        filePart: FilePart,
    ): UploadResult {
        // TODO: Audio monitoring needs redesign
        error("Audio monitoring configuration removed - needs redesign")
    }

    suspend fun streamProjectAudio(
        projectId: ObjectId,
        fileName: String,
        audioData: Flow<ByteArray>,
    ): UploadResult =
        withContext(Dispatchers.IO) {
            projectRepository.findById(projectId)
                ?: error("Project not found: $projectId")

            // TODO: Audio monitoring configuration removed - needs redesign
            error("Audio monitoring configuration removed - needs redesign")
        }
}
