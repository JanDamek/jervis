package com.jervis.service.meeting

import com.jervis.common.types.ClientId
import com.jervis.common.types.ProjectId
import com.jervis.common.types.SourceUrn
import com.jervis.domain.storage.DirectoryStructure
import com.jervis.dto.TaskTypeEnum
import com.jervis.dto.meeting.MeetingStateEnum
import com.jervis.entity.meeting.MeetingDocument
import com.jervis.repository.MeetingRepository
import com.jervis.rpc.WhisperSettingsRpcImpl
import com.jervis.service.background.TaskService
import com.jervis.service.storage.DirectoryStructureService
import jakarta.annotation.PostConstruct
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import mu.KotlinLogging
import org.bson.types.ObjectId
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.fileSize
import kotlin.streams.toList

private val logger = KotlinLogging.logger {}
private val jsonParser = Json { ignoreUnknownKeys = true }

/**
 * Continuous indexer for meeting recordings.
 *
 * STARTUP:
 * - Recovers stale RECORDING/UPLOADING meetings (server restart recovery)
 * - Syncs orphaned audio files on disk to DB
 *
 * PIPELINES:
 * 1. Poll for UPLOADED meetings -> run Whisper transcription -> TRANSCRIBED
 * 2. Poll for TRANSCRIBED meetings -> LLM correction -> CORRECTED
 * 3. Poll for CORRECTED meetings -> create MEETING_PROCESSING task for KB ingest -> INDEXED
 * 4. Trash auto-purge (30-day retention)
 */
@Service
@Order(12)
class MeetingContinuousIndexer(
    private val meetingRepository: MeetingRepository,
    private val meetingTranscriptionService: MeetingTranscriptionService,
    private val transcriptCorrectionService: TranscriptCorrectionService,
    private val taskService: TaskService,
    private val directoryStructureService: DirectoryStructureService,
    private val whisperSettingsRpc: WhisperSettingsRpcImpl,
    private val notificationRpc: com.jervis.rpc.NotificationRpcImpl,
) {
    private val supervisor = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + supervisor)

    /** Semaphore for parallel transcription. Re-created when settings change. */
    @Volatile
    private var transcriptionSemaphore = Semaphore(DEFAULT_PARALLEL_TRANSCRIPTIONS)

    @Volatile
    private var currentMaxParallelJobs = DEFAULT_PARALLEL_TRANSCRIPTIONS

    companion object {
        private const val POLL_DELAY_MS = 30_000L
        private const val TRASH_PURGE_INTERVAL_MS = 6 * 60 * 60 * 1000L // 6 hours
        private const val TRASH_RETENTION_DAYS = 30L
        private const val WAV_HEADER_SIZE = 44
        private const val BYTES_PER_SECOND = 32_000 // 16kHz, 16-bit, mono
        private const val DEFAULT_PARALLEL_TRANSCRIPTIONS = 3
    }

    @PostConstruct
    fun start() {
        logger.info { "Starting MeetingContinuousIndexer..." }

        // Startup: recover stale meetings + sync disk
        runBlocking {
            recoverStaleMeetings()
            syncDiskToDb()
        }

        // Pipeline 1: UPLOADED -> transcribe -> TRANSCRIBED
        scope.launch {
            runCatching { transcribeContinuously() }
                .onFailure { e -> logger.error(e) { "Meeting transcription pipeline crashed" } }
        }

        // Pipeline 2: TRANSCRIBED -> LLM correction -> CORRECTED
        scope.launch {
            runCatching { correctContinuously() }
                .onFailure { e -> logger.error(e) { "Meeting correction pipeline crashed" } }
        }

        // Pipeline 3: CORRECTED -> create task -> INDEXED
        scope.launch {
            runCatching { indexContinuously() }
                .onFailure { e -> logger.error(e) { "Meeting indexing pipeline crashed" } }
        }

        // Pipeline 4: Trash auto-purge (30-day retention)
        scope.launch {
            runCatching { trashPurgeContinuously() }
                .onFailure { e -> logger.error(e) { "Trash purge pipeline crashed" } }
        }
    }

    // ===== Startup: Stale Meeting Recovery =====

    /**
     * Single-instance server: after restart, any RECORDING/UPLOADING meetings are stale.
     * If audio file has useful data, promote to UPLOADED for processing.
     * Otherwise, clean up.
     */
    private suspend fun recoverStaleMeetings() {
        val staleStates = listOf(MeetingStateEnum.RECORDING, MeetingStateEnum.UPLOADING)
        for (state in staleStates) {
            meetingRepository.findByStateOrderByStoppedAtAsc(state).collect { meeting ->
                try {
                    val audioPath = meeting.audioFilePath?.let { Path.of(it) }
                    if (audioPath != null && audioPath.exists() && audioPath.fileSize() > WAV_HEADER_SIZE) {
                        val fileSize = audioPath.fileSize()
                        val duration = (fileSize - WAV_HEADER_SIZE) / BYTES_PER_SECOND
                        meetingRepository.save(
                            meeting.copy(
                                state = MeetingStateEnum.UPLOADED,
                                stoppedAt = Instant.now(),
                                audioSizeBytes = fileSize,
                                durationSeconds = duration,
                            ),
                        )
                        logger.info { "Recovered stale $state meeting ${meeting.id} -> UPLOADED ($fileSize bytes)" }
                    } else {
                        audioPath?.let { Files.deleteIfExists(it) }
                        meetingRepository.deleteById(meeting.id)
                        logger.info { "Deleted stale $state meeting ${meeting.id} (no useful audio data)" }
                    }
                } catch (e: Exception) {
                    logger.error(e) { "Error recovering stale meeting ${meeting.id}" }
                }
            }
        }
    }

    // ===== Startup: Disk-to-DB Sync =====

    /**
     * Scan audio directories on disk for orphaned meeting_*.wav files
     * that have no corresponding DB entry. Creates DB entries for processing.
     */
    private suspend fun syncDiskToDb() {
        try {
            val workspaceRoot = directoryStructureService.workspaceRoot()
            val clientsDir = workspaceRoot.resolve(DirectoryStructure.CLIENTS_DIR)
            if (!clientsDir.exists() || !clientsDir.isDirectory()) return

            withContext(Dispatchers.IO) {
                Files.list(clientsDir).toList()
            }.filter { it.isDirectory() }.forEach { clientDir ->
                val clientIdHex = clientDir.name
                if (!isValidObjectIdHex(clientIdHex)) return@forEach

                val clientId = ClientId.fromString(clientIdHex)

                // Scan client-level audio: clients/{clientId}/audio/meeting_*.wav
                val clientAudioDir = clientDir.resolve(DirectoryStructure.AUDIO_SUBDIR)
                scanDirForOrphanedMeetings(clientAudioDir, clientId, projectId = null)

                // Scan project-level meetings: clients/{clientId}/projects/*/meetings/meeting_*.wav
                val projectsDir = clientDir.resolve(DirectoryStructure.PROJECTS_SUBDIR)
                if (projectsDir.exists() && projectsDir.isDirectory()) {
                    withContext(Dispatchers.IO) {
                        Files.list(projectsDir).toList()
                    }.filter { it.isDirectory() }.forEach { projectDir ->
                        val projectIdHex = projectDir.name
                        if (!isValidObjectIdHex(projectIdHex)) return@forEach

                        val projectId = ProjectId.fromString(projectIdHex)
                        val meetingsDir = projectDir.resolve(DirectoryStructure.MEETINGS_SUBDIR)
                        scanDirForOrphanedMeetings(meetingsDir, clientId, projectId)
                    }
                }
            }
        } catch (e: Exception) {
            logger.error(e) { "Error during disk-to-DB sync" }
        }
    }

    private suspend fun scanDirForOrphanedMeetings(
        dir: Path,
        clientId: ClientId,
        projectId: ProjectId?,
    ) {
        if (!dir.exists() || !dir.isDirectory()) return

        val meetingFiles = withContext(Dispatchers.IO) {
            Files.list(dir).toList()
        }.filter {
            it.isRegularFile() && it.name.startsWith("meeting_") && it.name.endsWith(".wav")
        }

        for (file in meetingFiles) {
            try {
                val hexId = file.name.removePrefix("meeting_").removeSuffix(".wav")
                if (!isValidObjectIdHex(hexId)) continue

                val objectId = ObjectId(hexId)
                val existing = meetingRepository.findById(objectId)
                if (existing != null) continue // Already in DB

                val fileSize = file.fileSize()
                if (fileSize <= WAV_HEADER_SIZE) continue // Empty/header-only file

                val duration = (fileSize - WAV_HEADER_SIZE) / BYTES_PER_SECOND

                // Check for companion transcript file
                val transcriptFile = dir.resolve(file.name.replace(".wav", "_transcript.json"))
                val (state, transcriptText, transcriptSegments) = if (transcriptFile.exists()) {
                    try {
                        val content = withContext(Dispatchers.IO) { java.nio.file.Files.readString(transcriptFile) }
                        val result = jsonParser.decodeFromString<WhisperResult>(content)
                        if (result.error.isNullOrBlank() && result.text.isNotBlank()) {
                            val segments = result.segments.map { seg ->
                                com.jervis.entity.meeting.TranscriptSegment(
                                    startSec = seg.start,
                                    endSec = seg.end,
                                    text = seg.text.trim(),
                                )
                            }
                            Triple(MeetingStateEnum.TRANSCRIBED, result.text, segments)
                        } else {
                            Triple(MeetingStateEnum.UPLOADED, null, emptyList())
                        }
                    } catch (e: Exception) {
                        logger.warn(e) { "Failed to parse transcript file: ${transcriptFile.name}" }
                        Triple(MeetingStateEnum.UPLOADED, null, emptyList())
                    }
                } else {
                    Triple(MeetingStateEnum.UPLOADED, null, emptyList())
                }

                val document = MeetingDocument(
                    id = objectId,
                    clientId = clientId,
                    projectId = projectId,
                    state = state,
                    audioFilePath = file.toString(),
                    audioSizeBytes = fileSize,
                    durationSeconds = duration,
                    transcriptText = transcriptText,
                    transcriptSegments = transcriptSegments,
                    stoppedAt = Instant.now(),
                )
                meetingRepository.save(document)
                logger.info {
                    "Synced orphaned audio file to DB: ${file.name} " +
                        "(client=$clientId, project=$projectId, state=$state, ${fileSize} bytes, ${duration}s)"
                }
            } catch (e: Exception) {
                logger.warn(e) { "Error syncing orphaned file: ${file.name}" }
            }
        }
    }

    private fun isValidObjectIdHex(hex: String): Boolean =
        hex.length == 24 && hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }

    // ===== Pipeline 1: Transcription (parallel, up to MAX_PARALLEL_TRANSCRIPTIONS) =====

    private suspend fun transcribeContinuously() {
        // Load initial max parallel jobs from settings
        refreshMaxParallelJobs()

        while (true) {
            // Periodically refresh max parallel jobs setting
            refreshMaxParallelJobs()

            val meetings = meetingRepository
                .findByStateAndDeletedIsFalseOrderByStoppedAtAsc(MeetingStateEnum.UPLOADED)
                .toList()

            if (meetings.isEmpty()) {
                delay(POLL_DELAY_MS)
                continue
            }

            logger.info { "Found ${meetings.size} UPLOADED meetings for transcription (max parallel: $currentMaxParallelJobs)" }

            for (meeting in meetings) {
                transcriptionSemaphore.acquire()
                scope.launch {
                    try {
                        meetingTranscriptionService.transcribe(meeting)
                    } catch (e: Exception) {
                        logger.error(e) { "Failed to transcribe meeting ${meeting.id}" }
                        markAsFailed(meeting, "Transcription error: ${e.message}")
                    } finally {
                        transcriptionSemaphore.release()
                    }
                }
            }
        }
    }

    /**
     * Refresh the max parallel transcription jobs from DB settings.
     * Re-creates semaphore if the limit has changed.
     */
    private suspend fun refreshMaxParallelJobs() {
        try {
            val settings = whisperSettingsRpc.getSettingsDocument()
            if (settings.maxParallelJobs != currentMaxParallelJobs) {
                logger.info { "Whisper max parallel jobs changed: $currentMaxParallelJobs -> ${settings.maxParallelJobs}" }
                currentMaxParallelJobs = settings.maxParallelJobs
                transcriptionSemaphore = Semaphore(settings.maxParallelJobs)
            }
        } catch (e: Exception) {
            logger.warn(e) { "Failed to refresh whisper settings, using current: $currentMaxParallelJobs" }
        }
    }

    // ===== Pipeline 2: LLM Correction =====

    private suspend fun correctContinuously() {
        continuousMeetingsInState(MeetingStateEnum.TRANSCRIBED).collect { meeting ->
            try {
                transcriptCorrectionService.correct(meeting)
            } catch (e: Exception) {
                logger.error(e) { "Failed to correct meeting ${meeting.id}" }
                markAsFailed(meeting, "Correction error: ${e.message}")
            }
        }
    }

    // ===== Pipeline 3: KB Indexing =====

    private suspend fun indexContinuously() {
        continuousMeetingsInState(MeetingStateEnum.CORRECTED).collect { meeting ->
            try {
                indexMeeting(meeting)
            } catch (e: Exception) {
                logger.error(e) { "Failed to index meeting ${meeting.id}" }
                markAsFailed(meeting, "Indexing error: ${e.message}")
            }
        }
    }

    private suspend fun indexMeeting(meeting: MeetingDocument) {
        require(meeting.state == MeetingStateEnum.CORRECTED) {
            "Can only index CORRECTED meetings, got: ${meeting.state}"
        }

        // Use corrected transcript, fall back to raw
        val transcript = meeting.correctedTranscriptText ?: meeting.transcriptText
        if (transcript.isNullOrBlank()) {
            logger.warn { "Meeting ${meeting.id} has no transcript text, marking as FAILED" }
            markAsFailed(meeting, "No transcript text after correction")
            return
        }

        val meetingContent = buildMeetingContent(meeting)

        taskService.createTask(
            taskType = TaskTypeEnum.MEETING_PROCESSING,
            content = meetingContent,
            clientId = meeting.clientId,
            correlationId = "meeting:${meeting.id}",
            sourceUrn = SourceUrn.meeting(
                meetingId = meeting.id.toHexString(),
                title = meeting.title,
            ),
            projectId = meeting.projectId,
        )

        meetingRepository.save(meeting.copy(state = MeetingStateEnum.INDEXED))
        notificationRpc.emitMeetingStateChanged(
            meeting.id.toHexString(), meeting.clientId.toString(), MeetingStateEnum.INDEXED.name, meeting.title,
        )
        logger.info { "Created MEETING_PROCESSING task for meeting: ${meeting.title ?: meeting.id}" }
    }

    // ===== Pipeline 4: Trash Auto-Purge =====

    private suspend fun trashPurgeContinuously() {
        while (true) {
            delay(TRASH_PURGE_INTERVAL_MS)
            try {
                val cutoff = Instant.now().minus(Duration.ofDays(TRASH_RETENTION_DAYS))
                meetingRepository.findByDeletedIsTrueAndDeletedAtBefore(cutoff).collect { meeting ->
                    try {
                        meeting.audioFilePath?.let { path ->
                            withContext(Dispatchers.IO) {
                                Files.deleteIfExists(Path.of(path))
                            }
                        }
                        meetingRepository.deleteById(meeting.id)
                        logger.info {
                            "Auto-purged trashed meeting ${meeting.id} " +
                                "(deleted ${meeting.deletedAt}, title=${meeting.title})"
                        }
                    } catch (e: Exception) {
                        logger.warn(e) { "Error purging trashed meeting ${meeting.id}" }
                    }
                }
            } catch (e: Exception) {
                logger.error(e) { "Error in trash purge cycle" }
            }
        }
    }

    // ===== Shared Utilities =====

    private fun buildMeetingContent(meeting: MeetingDocument): String = buildString {
        val title = meeting.title ?: "Meeting ${meeting.id}"
        append("# $title\n\n")

        append("**Date:** ${meeting.startedAt}\n")
        meeting.durationSeconds?.let { dur ->
            val duration = Duration.ofSeconds(dur)
            val hours = duration.toHours()
            val minutes = duration.toMinutesPart()
            val seconds = duration.toSecondsPart()
            val formatted = if (hours > 0) {
                "${hours}h ${minutes}m ${seconds}s"
            } else {
                "${minutes}m ${seconds}s"
            }
            append("**Duration:** $formatted\n")
        }
        meeting.meetingType?.let { append("**Type:** ${it.name}\n") }
        meeting.audioInputType.let { append("**Audio Input:** ${it.name}\n") }
        append("\n---\n\n")

        append("## Transcript\n\n")

        // Prefer corrected segments over raw
        val segments = meeting.correctedTranscriptSegments.ifEmpty { meeting.transcriptSegments }
        if (segments.isNotEmpty()) {
            segments.forEach { seg ->
                val timestamp = formatTimestamp(seg.startSec)
                val speaker = seg.speaker?.let { "**$it:** " } ?: ""
                append("[$timestamp] $speaker${seg.text}\n")
            }
        } else {
            append(meeting.correctedTranscriptText ?: meeting.transcriptText ?: "")
        }

        append("\n\n## Source Metadata\n")
        append("- **Source Type:** Meeting\n")
        append("- **Meeting ID:** ${meeting.id}\n")
        append("- **Client ID:** ${meeting.clientId}\n")
        meeting.projectId?.let { append("- **Project ID:** $it\n") }
        meeting.title?.let { append("- **Title:** $it\n") }
        meeting.meetingType?.let { append("- **Meeting Type:** ${it.name}\n") }
        append("- **Started At:** ${meeting.startedAt}\n")
        meeting.stoppedAt?.let { append("- **Stopped At:** $it\n") }
    }

    private fun formatTimestamp(seconds: Double): String {
        val totalSec = seconds.toLong()
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) {
            "%d:%02d:%02d".format(h, m, s)
        } else {
            "%02d:%02d".format(m, s)
        }
    }

    private fun continuousMeetingsInState(state: MeetingStateEnum) =
        flow {
            while (true) {
                val meetings = meetingRepository.findByStateAndDeletedIsFalseOrderByStoppedAtAsc(state)

                var emittedAny = false
                meetings.collect { meeting ->
                    emit(meeting)
                    emittedAny = true
                }

                if (!emittedAny) {
                    logger.debug { "No $state meetings, sleeping ${POLL_DELAY_MS}ms" }
                    delay(POLL_DELAY_MS)
                } else {
                    logger.debug { "Processed $state meetings, immediately checking for more..." }
                }
            }
        }

    private suspend fun markAsFailed(meeting: MeetingDocument, error: String) {
        meetingRepository.save(
            meeting.copy(
                state = MeetingStateEnum.FAILED,
                errorMessage = error,
            ),
        )
        notificationRpc.emitMeetingStateChanged(
            meeting.id.toHexString(), meeting.clientId.toString(), MeetingStateEnum.FAILED.name, meeting.title, error,
        )
        logger.warn { "Marked meeting as FAILED: ${meeting.title ?: meeting.id}" }
    }
}
