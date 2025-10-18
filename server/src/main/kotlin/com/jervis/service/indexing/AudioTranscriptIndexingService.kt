package com.jervis.service.indexing

import com.jervis.configuration.AudioMonitoringProperties
import com.jervis.configuration.AudioTranscriptionProperties
import com.jervis.domain.model.ModelType
import com.jervis.domain.rag.RagDocument
import com.jervis.domain.rag.RagSourceType
import com.jervis.entity.mongo.ClientDocument
import com.jervis.entity.mongo.ProjectDocument
import com.jervis.repository.vector.VectorStorageRepository
import com.jervis.service.gateway.EmbeddingGateway
import com.jervis.service.gateway.WhisperGateway
import com.jervis.service.indexing.monitoring.IndexingMonitorService
import com.jervis.service.indexing.monitoring.IndexingStepStatus
import com.jervis.service.indexing.monitoring.IndexingStepType
import com.jervis.service.rag.RagIndexingStatusService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import org.bson.types.ObjectId
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.nameWithoutExtension
import kotlin.io.path.pathString

/**
 * Minimal audio transcript indexing service placeholder.
 * Accepts uploaded audio files and logs that a transcription job was enqueued.
 * Actual transcription and RAG indexing can be implemented later and wired here.
 * Service for indexing audio files by transcribing them and storing transcript embeddings.
 * Minimal audio transcript indexing facade.
 *
 * This service provides a narrow API used by AudioMonitoringService to trigger (re)indexing.
 * For now it only scans for supported audio files and logs counts, acting as an integration point
 * where full audio-to-text processing (e.g., Whisper) can be plugged in later.
 * Service for indexing audio files by transcribing them into text.
 * Minimal implementation to integrate with existing monitoring and indexing pipeline.
 */
@Service
class AudioTranscriptIndexingService(
    private val whisperGateway: WhisperGateway,
    private val embeddingGateway: EmbeddingGateway,
    private val vectorStorage: VectorStorageRepository,
    private val ragIndexingStatusService: RagIndexingStatusService,
    private val historicalVersioningService: HistoricalVersioningService,
    private val indexingMonitorService: IndexingMonitorService,
    private val audioProps: AudioTranscriptionProperties,
    private val audioMonitoringProps: AudioMonitoringProperties,
) {
    companion object {
        private val logger = KotlinLogging.logger {}
        private const val UNKNOWN = "unknown"
        private const val AUDIO_MODULE = "audio"
        private const val MIN_SENTENCE_LENGTH = 10
    }

    data class TranscriptionJob(
        val fileName: String,
        val filePath: Path,
        val source: String,
    )

    data class AudioIndexingResult(
        val processedFiles: Int,
        val skippedFiles: Int,
        val errorFiles: Int,
        val totalTranscriptionTimeSeconds: Long,
        val processedAudios: Int,
        val skippedAudios: Int,
        val errorAudios: Int,
    )

    data class AudioMetadata(
        val fileName: String,
        val filePath: Path,
        val source: String,
        val format: String,
        val durationSeconds: Float? = null,
        val language: String? = null,
    )

    private enum class Scope(
        val label: String,
    ) {
        PROJECT("project"),
        CLIENT("client"),
    }

    /**
     * Scan project's configured audioPath and prepare transcripts for indexing.
     * Current minimal implementation validates configuration and reports monitoring progress.
     */
    suspend fun indexProjectAudioFiles(project: ProjectDocument): AudioIndexingResult =
        withContext(Dispatchers.IO) {
            logger.info { "Starting audio indexing for project: ${project.name}" }
            logger.info { "Starting audio transcript indexing for project: ${project.name}" }

            val audioPath = project.overrides.audioMonitoring?.audioPath
            if (audioPath.isNullOrBlank()) {
                logger.info { "No audio path configured for project: ${project.name}" }
                return@withContext AudioIndexingResult(0, 0, 0, 0, 0, 0, 0)
            }

            val projectPathString = project.projectPath
            val projectPath = if (projectPathString != null) Paths.get(projectPathString) else null

            indexAudioDirectory(
                audioPath = audioPath,
                projectId = project.id,
                clientId = project.clientId,
                projectPath = projectPath,
                scope = Scope.PROJECT,
            )
        }

    suspend fun indexClientAudioFiles(
        client: ClientDocument,
        clientAudioPath: String,
    ): AudioIndexingResult =
        withContext(Dispatchers.IO) {
            logger.info { "Starting audio indexing for client: ${client.name}" }

            indexAudioDirectory(
                audioPath = clientAudioPath,
                projectId = null,
                clientId = client.id,
                projectPath = null,
                scope = Scope.CLIENT,
            )
        }

    private suspend fun indexAudioDirectory(
        audioPath: String,
        projectId: ObjectId?,
        clientId: ObjectId,
        projectPath: Path?,
        scope: Scope,
    ): AudioIndexingResult {
        val audioDir = Paths.get(audioPath)
        if (!Files.exists(audioDir)) {
            projectId?.let {
                indexingMonitorService.updateStepProgress(
                    it,
                    IndexingStepType.AUDIO_TRANSCRIPTS,
                    IndexingStepStatus.FAILED,
                    errorMessage = "Audio path does not exist: $audioPath",
                    logs = listOf("Audio path does not exist: $audioPath"),
                )
            }
            logger.warn { "Audio path does not exist: $audioPath" }
            return AudioIndexingResult(0, 0, 1, 0, 0, 0, 1)
        }

        val gitCommitHash =
            projectPath?.let { historicalVersioningService.getCurrentGitCommitHash(it) }
                ?: "audio-${System.currentTimeMillis()}"

        var processedFiles = 0
        var skippedFiles = 0
        var errorFiles = 0
        var totalTranscriptionTime = 0L

        try {
            val audioFiles = findSupportedAudioFiles(audioDir)

            logger.info { "Found ${audioFiles.size} audio files to process in ${scope.label} scope" }
            projectId?.let {
                indexingMonitorService.addStepLog(
                    it,
                    IndexingStepType.MEETING_TRANSCRIPTS,
                    "Found ${audioFiles.size} audio files to transcribe",
                )
            }

            for ((index, audioFile) in audioFiles.withIndex()) {
                val startTime = System.currentTimeMillis()
                try {
                    val relativePath = audioDir.relativize(audioFile).toString()
                    projectId?.let {
                        indexingMonitorService.addStepLog(
                            it,
                            IndexingStepType.MEETING_TRANSCRIPTS,
                            "Processing audio file (${index + 1}/${audioFiles.size}): $relativePath",
                        )
                    }

                    val fileBytes = Files.readAllBytes(audioFile)

                    val projectOrClientId = projectId ?: clientId
                    val virtualPath = "audio/${scope.label}/$relativePath"

                    val shouldIndex =
                        ragIndexingStatusService.shouldIndexFile(
                            projectId = projectOrClientId,
                            filePath = virtualPath,
                            gitCommitHash = gitCommitHash,
                            fileContent = fileBytes,
                        )

                    if (!shouldIndex) {
                        skippedFiles++
                        logger.debug { "Skipping already indexed audio file: $relativePath" }
                        continue
                    }

                    ragIndexingStatusService.startIndexing(
                        projectId = projectOrClientId,
                        filePath = virtualPath,
                        gitCommitHash = gitCommitHash,
                        fileContent = fileBytes,
                        language = AUDIO_MODULE,
                        module = AUDIO_MODULE,
                    )

                    val transcription =
                        whisperGateway.transcribeAudioFile(
                            audioFile = audioFile,
                            model = audioProps.model,
                            language = audioProps.language,
                        )

                    val metadata =
                        AudioMetadata(
                            fileName = audioFile.fileName.toString(),
                            filePath = audioFile,
                            source = "${scope.label}:$projectOrClientId",
                            format = audioFile.extension,
                            durationSeconds = transcription.duration,
                            language = transcription.language,
                        )

                    val sentences = createTranscriptSentences(transcription, metadata)

                    logger.debug { "Split audio transcript $relativePath into ${sentences.size} sentences" }

                    var successfulSentences = 0
                    for (sentenceIndex in sentences.indices) {
                        try {
                            val sentence = sentences[sentenceIndex]
                            val embedding = embeddingGateway.callEmbedding(ModelType.EMBEDDING_TEXT, sentence)

                            val ragDocument =
                                RagDocument(
                                    projectId = projectOrClientId,
                                    clientId = clientId,
                                    ragSourceType = RagSourceType.AUDIO_TRANSCRIPT,
                                    summary = sentence,
                                    language = metadata.language ?: UNKNOWN,
                                    gitCommitHash = gitCommitHash,
                                    chunkId = sentenceIndex,
                                    symbolName = "audio-${audioFile.nameWithoutExtension}",
                                )

                            vectorStorage.store(ModelType.EMBEDDING_TEXT, ragDocument, embedding)
                            successfulSentences++
                        } catch (e: Exception) {
                            logger.error(e) { "Error storing sentence $sentenceIndex for audio $relativePath" }
                        }
                    }

                    logger.info { "Successfully indexed audio $relativePath as $successfulSentences/${sentences.size} sentences" }

                    processedFiles++
                    val elapsedTime = (System.currentTimeMillis() - startTime) / 1000
                    totalTranscriptionTime += elapsedTime

                    projectId?.let {
                        indexingMonitorService.addStepLog(
                            it,
                            IndexingStepType.MEETING_TRANSCRIPTS,
                            "✓ Successfully transcribed and indexed: $relativePath (${sentences.size} sentences, ${elapsedTime}s)",
                        )
                    }
                } catch (e: Exception) {
                    val relativePath = audioDir.relativize(audioFile).toString()
                    projectId?.let {
                        indexingMonitorService.addStepLog(
                            it,
                            IndexingStepType.MEETING_TRANSCRIPTS,
                            "✗ Failed to process audio file: $relativePath - ${e.message}",
                        )
                    }
                    logger.warn(e) { "Failed to process audio file: ${audioFile.pathString}" }
                    errorFiles++
                }
            }
        } catch (e: Exception) {
            logger.error(e) { "Error during audio indexing for ${scope.label} scope" }
            errorFiles++
        }

        logger.info {
            "Audio indexing completed for ${scope.label} - " +
                "Processed: $processedFiles, Skipped: $skippedFiles, Errors: $errorFiles, " +
                "Total time: ${totalTranscriptionTime}s"
        }

        return AudioIndexingResult(
            processedFiles = processedFiles,
            skippedFiles = skippedFiles,
            errorFiles = errorFiles,
            totalTranscriptionTimeSeconds = totalTranscriptionTime,
            processedAudios = processedFiles,
            skippedAudios = skippedFiles,
            errorAudios = errorFiles,
        )
    }

    private fun findSupportedAudioFiles(root: Path): List<Path> {
        if (!Files.exists(root)) return emptyList()
        val regex =
            Regex(
                ".*\\.(" + audioMonitoringProps.supportedFormats.joinToString("|") { Regex.escape(it) } + ")$",
                RegexOption.IGNORE_CASE,
            )
        return Files
            .walk(root)
            .filter { it.isRegularFile() }
            .filter { it.toString().matches(regex) }
            .toList()
    }

    private fun createTranscriptSentences(
        transcription: WhisperGateway.WhisperTranscriptionResponse,
        metadata: AudioMetadata,
    ): List<String> {
        val sentences = mutableListOf<String>()

        sentences.add(
            buildString {
                append("Audio file '")
                append(metadata.fileName)
                append("' in ")
                append(metadata.format)
                append(" format")
                metadata.durationSeconds?.let { append(", duration: ${it.toInt()}s") }
                metadata.language?.let { append(", language: $it") }
            },
        )

        if (transcription.segments.isNotEmpty()) {
            transcription.segments.forEach { segment ->
                val text = segment.text.trim()
                if (text.isNotEmpty() && text.length >= MIN_SENTENCE_LENGTH) {
                    sentences.add(text)
                }
            }
        } else {
            val textSentences =
                transcription.text
                    .split(Regex("[.!?\\n]+"))
                    .map { it.trim() }
                    .filter { it.length >= MIN_SENTENCE_LENGTH }
            sentences.addAll(textSentences)
        }

        return sentences.filter { it.isNotEmpty() }
    }

    suspend fun enqueueTranscription(job: TranscriptionJob) {
        logger.info { "Enqueued transcription job for ${job.source}: ${job.fileName} at ${job.filePath}" }
    }
}
