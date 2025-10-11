package com.jervis.service.indexing

import com.jervis.entity.mongo.ClientDocument
import com.jervis.entity.mongo.ProjectDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.isRegularFile

/**
 * Minimal audio transcript indexing facade.
 *
 * This service provides a narrow API used by AudioMonitoringService to trigger (re)indexing.
 * For now it only scans for supported audio files and logs counts, acting as an integration point
 * where full audio-to-text processing (e.g., Whisper) can be plugged in later.
 */
@Service
class AudioTranscriptIndexingService(
    @Value("\${audio.monitoring.supported-formats}")
    private val supportedFormats: List<String>,
) {
    private val logger = KotlinLogging.logger {}

    suspend fun indexProjectAudioFiles(project: ProjectDocument) = withContext(Dispatchers.IO) {
        val audioPath = project.audioPath
        if (audioPath.isNullOrBlank()) {
            logger.info { "No audio path configured for project: ${project.name}" }
            return@withContext
        }
        val audioDir = Paths.get(audioPath)
        val files = findSupportedAudioFiles(audioDir)
        logger.info { "Audio indexing requested for project ${project.name}: ${files.size} file(s) detected in $audioPath" }
        // Placeholder for real indexing pipeline.
    }

    suspend fun indexClientAudioFiles(client: ClientDocument, audioPath: String) = withContext(Dispatchers.IO) {
        val audioDir = Paths.get(audioPath)
        val files = findSupportedAudioFiles(audioDir)
        logger.info { "Audio indexing requested for client ${client.name}: ${files.size} file(s) detected in $audioPath" }
        // Placeholder for real indexing pipeline.
    }

    private fun findSupportedAudioFiles(root: Path): List<Path> {
        if (!Files.exists(root)) return emptyList()
        val regex = Regex(".*\\.(" + supportedFormats.joinToString("|") { Regex.escape(it) } + ")$", RegexOption.IGNORE_CASE)
        return Files.walk(root)
            .filter { it.isRegularFile() }
            .filter { it.toString().matches(regex) }
            .toList()
    }
}
