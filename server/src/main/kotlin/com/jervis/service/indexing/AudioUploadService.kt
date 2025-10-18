package com.jervis.service.indexing

import com.jervis.entity.mongo.ClientDocument
import com.jervis.entity.mongo.ProjectDocument
import com.jervis.repository.mongo.ClientMongoRepository
import com.jervis.repository.mongo.ProjectMongoRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.fold
import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import org.bson.types.ObjectId
import org.springframework.http.codec.multipart.FilePart
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption

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
            val project: ProjectDocument =
                projectRepository.findById(projectId)
                    ?: error("Project not found: ${'$'}projectId")

            val audioPath =
                project.overrides.audioMonitoring?.audioPath ?: error("Project has no audio path configured")

            val fileName = filePart.filename()
            val targetPath = Paths.get(audioPath).resolve(fileName)

            Files.createDirectories(targetPath.parent)

            // Save the uploaded file using reactive -> coroutine bridge
            filePart.transferTo(targetPath).awaitFirstOrNull()

            val fileSize = Files.size(targetPath)
            logger.info { "Uploaded audio file for project ${'$'}{project.name}: ${'$'}fileName (${'$'}fileSize bytes)" }

            audioTranscriptIndexingService.enqueueTranscription(
                AudioTranscriptIndexingService.TranscriptionJob(
                    fileName = fileName,
                    filePath = targetPath,
                    source = "project:${'$'}{project.id}",
                ),
            )

            UploadResult(fileName, fileSize, targetPath)
        }

    suspend fun uploadClientAudio(
        clientId: ObjectId,
        filePart: FilePart,
    ): UploadResult =
        withContext(Dispatchers.IO) {
            val client: ClientDocument =
                clientRepository.findById(clientId)
                    ?: error("Client not found: ${'$'}clientId")

            val audioPath = client.audioPath ?: error("Client has no audio path configured")

            val fileName = filePart.filename()
            val targetPath = Paths.get(audioPath).resolve(fileName)

            Files.createDirectories(targetPath.parent)
            filePart.transferTo(targetPath).awaitFirstOrNull()

            val fileSize = Files.size(targetPath)
            logger.info { "Uploaded audio file for client ${'$'}{client.name}: ${'$'}fileName (${'$'}fileSize bytes)" }

            audioTranscriptIndexingService.enqueueTranscription(
                AudioTranscriptIndexingService.TranscriptionJob(
                    fileName = fileName,
                    filePath = targetPath,
                    source = "client:${'$'}{client.id}",
                ),
            )

            UploadResult(fileName, fileSize, targetPath)
        }

    suspend fun streamProjectAudio(
        projectId: ObjectId,
        fileName: String,
        audioData: Flow<ByteArray>,
    ): UploadResult =
        withContext(Dispatchers.IO) {
            val project: ProjectDocument =
                projectRepository.findById(projectId)
                    ?: error("Project not found: $projectId")

            val audioPath =
                project.overrides.audioMonitoring?.audioPath ?: error("Project has no audio path configured")

            val targetPath = Paths.get(audioPath).resolve(fileName)
            Files.createDirectories(targetPath.parent)

            val fileSize =
                audioData.fold(0L) { totalBytes, chunk ->
                    Files.write(
                        targetPath,
                        chunk,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND,
                    )
                    totalBytes + chunk.size
                }

            logger.info { "Streamed audio file for project ${'$'}{project.name}: ${'$'}fileName (${'$'}fileSize bytes)" }

            audioTranscriptIndexingService.enqueueTranscription(
                AudioTranscriptIndexingService.TranscriptionJob(
                    fileName = fileName,
                    filePath = targetPath,
                    source = "project:${'$'}{project.id}",
                ),
            )

            UploadResult(fileName, fileSize, targetPath)
        }
}
