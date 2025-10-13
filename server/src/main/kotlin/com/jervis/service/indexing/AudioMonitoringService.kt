package com.jervis.service.indexing

import com.jervis.configuration.AudioMonitoringProperties
import com.jervis.entity.mongo.ClientDocument
import com.jervis.entity.mongo.ProjectDocument
import com.jervis.repository.mongo.ClientMongoRepository
import com.jervis.repository.mongo.ProjectMongoRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Instant

/**
 * Service for monitoring audio directories and triggering reindexing based on git changes.
 * Implements cascading configuration: Project → Global defaults.
 */
@Service
class AudioMonitoringService(
    private val projectRepository: ProjectMongoRepository,
    private val clientRepository: ClientMongoRepository,
    private val audioTranscriptIndexingService: AudioTranscriptIndexingService,
    private val historicalVersioningService: HistoricalVersioningService,
    private val audioMonitoringProps: AudioMonitoringProperties,
    private val pathResolver: com.jervis.util.PathResolver,
) {
    private val logger = KotlinLogging.logger {}
    private val lastCheckedCommits = mutableMapOf<String, String>()
    private val lastCheckTimes = mutableMapOf<String, Instant>()

    @Scheduled(fixedDelay = 60000)
    suspend fun monitorAudioDirectories() {
        try {
            monitorProjectAudioDirectories()
            monitorClientAudioDirectories()
        } catch (e: Exception) {
            logger.error(e) { "Error during audio directory monitoring" }
        }
    }

    private suspend fun monitorProjectAudioDirectories() =
        withContext(Dispatchers.IO) {
            val projects = projectRepository.findAll().toList()
            for (project in projects) {
                if (project.isDisabled) continue

                val audioDir = pathResolver.projectAudioDir(project)
                if (!Files.exists(audioDir)) {
                    try { Files.createDirectories(audioDir) } catch (e: Exception) {
                        logger.warn(e) { "Failed to create audio directory for project ${project.name}: $audioDir" }
                        continue
                    }
                }

                try {
                    val interval = getEffectiveInterval(project)
                    if (shouldCheckDirectory(audioDir.toString(), interval)) {
                        checkAndReindexProject(project, audioDir)
                    }
                } catch (e: Exception) {
                    logger.error(e) { "Error monitoring audio for project: ${project.name}" }
                }
            }
        }

    private suspend fun monitorClientAudioDirectories() =
        withContext(Dispatchers.IO) {
            val clients = clientRepository.findAll().toList()
            for (client in clients) {
                if (client.isDisabled) continue

                val audioDir = pathResolver.clientAudioDir(client)
                if (!Files.exists(audioDir)) {
                    try { Files.createDirectories(audioDir) } catch (e: Exception) {
                        logger.warn(e) { "Failed to create audio directory for client ${client.name}: $audioDir" }
                        continue
                    }
                }

                try {
                    val interval = audioMonitoringProps.defaultGitCheckIntervalMinutes
                    if (shouldCheckDirectory(audioDir.toString(), interval)) {
                        checkAndReindexClient(client, audioDir)
                    }
                } catch (e: Exception) {
                    logger.error(e) { "Error monitoring audio for client: ${client.name}" }
                }
            }
        }

    private suspend fun checkAndReindexProject(
        project: ProjectDocument,
        audioDir: Path,
    ) {
        val projectPath = pathResolver.projectGitDir(project)
        val currentCommit = historicalVersioningService.getCurrentGitCommitHash(projectPath) ?: return

        val lastCommit = lastCheckedCommits[audioDir.toString()]
        if (lastCommit == null || lastCommit != currentCommit) {
            logger.info {
                "Git change detected in project ${project.name} audio directory. " +
                    "Reindexing... (commit: $lastCommit → $currentCommit)"
            }

            audioTranscriptIndexingService.indexProjectAudioFiles(project)
            lastCheckedCommits[audioDir.toString()] = currentCommit
        }
    }

    private suspend fun checkAndReindexClient(
        client: ClientDocument,
        audioDir: Path,
    ) {
        val markerPath = audioDir.resolve(".last_indexed")

        val lastModified =
            if (Files.exists(markerPath)) Files.getLastModifiedTime(markerPath).toInstant() else Instant.MIN

        val latestFileModified =
            Files
                .walk(audioDir)
                .filter { Files.isRegularFile(it) }
                .filter {
                    it.toString().matches(Regex(".*\\.(wav|mp3|m4a|flac|ogg|opus|webm)$", RegexOption.IGNORE_CASE))
                }.map { Files.getLastModifiedTime(it).toInstant() }
                .max(Comparator.naturalOrder())
                .orElse(Instant.MIN)

        if (latestFileModified.isAfter(lastModified)) {
            logger.info { "File changes detected in client ${client.name} audio directory. Reindexing..." }
            audioTranscriptIndexingService.indexClientAudioFiles(client, audioDir.toString())

            Files.createDirectories(audioDir)
            Files.writeString(markerPath, Instant.now().toString())
        }
    }

    private fun getEffectiveInterval(project: ProjectDocument): Long =
        project.overrides.audioMonitoring?.gitCheckIntervalMinutes
            ?: audioMonitoringProps.defaultGitCheckIntervalMinutes

    private fun shouldCheckDirectory(
        audioPath: String,
        intervalMinutes: Long,
    ): Boolean {
        val now = Instant.now()
        val lastCheck = lastCheckTimes[audioPath] ?: Instant.MIN
        val minutesSinceLastCheck = (now.epochSecond - lastCheck.epochSecond) / 60
        return if (minutesSinceLastCheck >= intervalMinutes) {
            lastCheckTimes[audioPath] = now
            true
        } else {
            false
        }
    }
}
