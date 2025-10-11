package com.jervis.service.indexing

import com.jervis.entity.mongo.ProjectDocument
import com.jervis.service.indexing.monitoring.IndexingMonitorService
import com.jervis.service.indexing.monitoring.IndexingStepStatus
import com.jervis.service.indexing.monitoring.IndexingStepType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Paths

/**
 * Service for indexing audio files by transcribing them into text.
 * Minimal implementation to integrate with existing monitoring and indexing pipeline.
 */
@Service
class AudioTranscriptIndexingService(
    private val indexingMonitorService: IndexingMonitorService,
) {
    companion object {
        private val logger = KotlinLogging.logger {}
    }

    /** Result of audio indexing operation. */
    data class AudioIndexingResult(
        val processedAudios: Int,
        val skippedAudios: Int,
        val errorAudios: Int,
    )

    /**
     * Scan project's configured audioPath and prepare transcripts for indexing.
     * Current minimal implementation validates configuration and reports monitoring progress.
     */
    suspend fun indexProjectAudioFiles(project: ProjectDocument): AudioIndexingResult =
        withContext(Dispatchers.IO) {
            logger.info { "Starting audio transcript indexing for project: ${project.name}" }

            val audioPath = project.audioPath
            if (audioPath.isNullOrBlank()) {
                logger.info { "No audio path configured for project: ${project.name}" }
                // Treat as skipped rather than error to avoid failing the whole indexing
                return@withContext AudioIndexingResult(0, 0, 0)
            }

            val audioDir = Paths.get(audioPath)
            if (!Files.exists(audioDir)) {
                indexingMonitorService.updateStepProgress(
                    project.id,
                    IndexingStepType.AUDIO_TRANSCRIPTS,
                    IndexingStepStatus.FAILED,
                    errorMessage = "Audio path does not exist: $audioPath",
                )
                logger.warn { "Audio path does not exist: $audioPath" }
                return@withContext AudioIndexingResult(0, 0, 1)
            }

            // Minimal stub implementation: mark as completed with zero processed
            indexingMonitorService.updateStepProgress(
                project.id,
                IndexingStepType.AUDIO_TRANSCRIPTS,
                IndexingStepStatus.COMPLETED,
                message = "No-op audio indexing (stub)",
            )
            AudioIndexingResult(processedAudios = 0, skippedAudios = 0, errorAudios = 0)
        }
}
