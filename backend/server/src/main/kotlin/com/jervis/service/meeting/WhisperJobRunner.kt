package com.jervis.service.meeting

import com.jervis.configuration.CorrectionListRequestDto
import com.jervis.configuration.PythonOrchestratorClient
import com.jervis.entity.WhisperSettingsDocument
import com.jervis.rpc.WhisperSettingsRpcImpl
import io.fabric8.kubernetes.api.model.EnvVarBuilder
import io.fabric8.kubernetes.api.model.PersistentVolumeClaimVolumeSourceBuilder
import io.fabric8.kubernetes.api.model.VolumeBuilder
import io.fabric8.kubernetes.api.model.VolumeMountBuilder
import io.fabric8.kubernetes.api.model.batch.v1.JobBuilder
import io.fabric8.kubernetes.client.ConfigBuilder
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.KubernetesClientBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import mu.KotlinLogging
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.UUID

private val logger = KotlinLogging.logger {}

private val json = Json { ignoreUnknownKeys = true }

/**
 * Runs Whisper transcription via one of three modes:
 * 1. **K8s Job** (in-cluster, `deploymentMode=k8s_job`) — spawns a K8s Job with PVC audio/result sharing
 * 2. **REST Remote** (`deploymentMode=rest_remote`) — sends audio to a persistent Whisper REST server via HTTP
 * 3. **Local subprocess** (development, not in K8s) — runs whisper_runner.py directly
 *
 * Reads configurable parameters from [WhisperSettingsDocument] via [WhisperSettingsRpcImpl].
 * Progress tracking: K8s Job mode uses PVC file polling, REST mode uses SSE streaming events.
 */
@Service
class WhisperJobRunner(
    private val whisperSettingsRpc: WhisperSettingsRpcImpl,
    private val notificationRpc: com.jervis.rpc.NotificationRpcImpl,
    private val orchestratorClient: PythonOrchestratorClient,
    private val whisperRestClient: WhisperRestClient,
) {

    companion object {
        private val WHISPER_IMAGE =
            System.getenv("WHISPER_IMAGE") ?: "registry.damek-soft.eu/jandamek/jervis-whisper:latest"
        private val K8S_NAMESPACE = System.getenv("K8S_NAMESPACE") ?: "jervis"
        private val PVC_NAME = System.getenv("DATA_PVC_NAME") ?: "jervis-data-pvc"
        private val PVC_MOUNT = System.getenv("DATA_PVC_MOUNT") ?: "/opt/jervis/data"
        private val IN_CLUSTER = Paths.get("/var/run/secrets/kubernetes.io/serviceaccount/token").toFile().exists()
        private const val POLL_INTERVAL_SECONDS = 10L
        private const val PROGRESS_LOG_INTERVAL = 1 // Log + emit progress every poll
        private const val WAV_HEADER_SIZE = 44
        private const val BYTES_PER_SECOND = 32_000 // 16kHz, 16-bit, mono
    }

    private fun buildK8sClient(): KubernetesClient {
        val config = ConfigBuilder()
            .withRequestTimeout(300_000)     // 5 min for API requests
            .withConnectionTimeout(30_000)   // 30s for initial connection
            .build()
        return KubernetesClientBuilder()
            .withConfig(config)
            .build()
    }

    /**
     * Check if Whisper transcription is available.
     * REST_REMOTE: checks /health on remote server.
     * K8S_JOB: checks K8s API accessibility (always true locally).
     */
    suspend fun isAvailable(): Boolean {
        val settings = whisperSettingsRpc.getSettingsDocument()
        if (settings.deploymentMode == "rest_remote") {
            return whisperRestClient.isHealthy(settings.restRemoteUrl)
        }
        if (!IN_CLUSTER) return true
        return try {
            withContext(Dispatchers.IO) {
                buildK8sClient().use { client ->
                    client.batch().v1().jobs()
                        .inNamespace(K8S_NAMESPACE)
                        .withLabel("app", "jervis-whisper")
                        .list()
                    true
                }
            }
        } catch (e: Exception) {
            logger.warn(e) { "Whisper K8s health check failed" }
            false
        }
    }

    /**
     * Transcribe an audio file using Whisper.
     *
     * @param audioFilePath Absolute path to the audio file on PVC
     * @param workspacePath Absolute path to the workspace directory on PVC
     * @return WhisperResult with transcription text and segments
     */
    suspend fun transcribe(
        audioFilePath: String,
        workspacePath: String,
        meetingId: String? = null,
        clientId: String? = null,
        projectId: String? = null,
    ): WhisperResult {
        val settings = whisperSettingsRpc.getSettingsDocument()

        // Auto-build initial_prompt from KB corrections for this client/project
        val autoPrompt = buildInitialPromptFromCorrections(clientId, projectId)

        // Per-meeting result file — enables parallel Whisper Jobs without collision
        val audioPath = Paths.get(audioFilePath)
        val resultFileName = audioPath.fileName.toString().replace(".wav", "_transcript.json")
        val resultFile = audioPath.parent.resolve(resultFileName)
        val progressFile = audioPath.parent.resolve(resultFileName.replace("_transcript.json", "_progress.json"))

        withContext(Dispatchers.IO) {
            Files.createDirectories(resultFile.parent)
            Files.deleteIfExists(resultFile)
            Files.deleteIfExists(progressFile)
        }

        // Compute dynamic timeout based on audio duration and settings
        val audioFileSize = withContext(Dispatchers.IO) { Files.size(Paths.get(audioFilePath)) }
        val audioDurationSeconds = (audioFileSize - WAV_HEADER_SIZE).coerceAtLeast(0) / BYTES_PER_SECOND
        val timeoutSeconds = (audioDurationSeconds * settings.timeoutMultiplier).coerceAtLeast(settings.minTimeoutSeconds.toLong())
        logger.info {
            "Whisper timeout: ${timeoutSeconds}s (audio: ${audioDurationSeconds}s, file: ${audioFileSize} bytes, " +
                "model=${settings.model}, lang=${settings.language ?: "auto"}, beam=${settings.beamSize}, vad=${settings.vadFilter})"
        }

        // Build options JSON for whisper_runner.py
        val optionsJson = buildOptionsJson(settings, progressFile.toString(), autoPrompt)

        // REST_REMOTE mode: send audio over HTTP SSE stream instead of K8s Job / local subprocess
        if (settings.deploymentMode == "rest_remote") {
            logger.info { "Using REST remote mode: ${settings.restRemoteUrl}" }
            return whisperRestClient.transcribe(
                baseUrl = settings.restRemoteUrl,
                audioFilePath = audioFilePath,
                optionsJson = optionsJson,
                onProgress = buildProgressCallback(meetingId, clientId),
            )
        }

        try {
            if (IN_CLUSTER) {
                runK8sJob(audioFilePath, workspacePath, timeoutSeconds, resultFile, progressFile, optionsJson, meetingId, clientId, modelName = settings.model)
            } else {
                runLocal(audioFilePath, resultFile, optionsJson)
            }

            // Read result
            return withContext(Dispatchers.IO) {
                if (Files.exists(resultFile)) {
                    val content = Files.readString(resultFile)
                    json.decodeFromString<WhisperResult>(content)
                } else {
                    WhisperResult(text = "", segments = emptyList(), error = "Whisper completed but no result file found")
                }
            }
        } finally {
            // Clean up result and progress files
            withContext(Dispatchers.IO) {
                Files.deleteIfExists(resultFile)
                Files.deleteIfExists(progressFile)
            }
        }
    }

    /**
     * Re-transcribe specific audio ranges with high-accuracy settings.
     *
     * Used for "Nevim" (I don't know) answers: extracts audio around unclear segments,
     * re-transcribes with large-v3 + beam_size=10 for best accuracy.
     *
     * @param audioFilePath Source audio file on PVC
     * @param workspacePath Workspace directory on PVC
     * @param extractionRanges Time ranges to extract: [{start, end, segment_index}, ...]
     * @return WhisperResult with text_by_segment mapping segment indices to re-transcribed text
     */
    suspend fun retranscribe(
        audioFilePath: String,
        workspacePath: String,
        extractionRanges: List<ExtractionRange>,
        meetingId: String? = null,
        clientId: String? = null,
        projectId: String? = null,
    ): WhisperResult {
        val autoPrompt = buildInitialPromptFromCorrections(clientId, projectId)

        val audioPath = Paths.get(audioFilePath)
        val resultFileName = audioPath.fileName.toString().replace(".wav", "_retranscribe.json")
        val resultFile = audioPath.parent.resolve(resultFileName)
        val progressFile = audioPath.parent.resolve(resultFileName.replace("_retranscribe.json", "_retranscribe_progress.json"))

        withContext(Dispatchers.IO) {
            Files.createDirectories(resultFile.parent)
            Files.deleteIfExists(resultFile)
            Files.deleteIfExists(progressFile)
        }

        // Total extracted audio duration for timeout calculation
        val totalExtractedSeconds = extractionRanges.sumOf { it.end - it.start }.toLong()
        // large-v3 on CPU is ~5-10x real-time, use generous timeout
        val timeoutSeconds = (totalExtractedSeconds * 15).coerceAtLeast(600L)

        logger.info {
            "Retranscription: ${extractionRanges.size} ranges, " +
                "${totalExtractedSeconds}s extracted audio, timeout=${timeoutSeconds}s"
        }

        // Build extraction ranges JSON for whisper_runner.py
        val rangesJson = extractionRanges.joinToString(",", "[", "]") { r ->
            """{"start":${r.start},"end":${r.end},"segment_index":${r.segmentIndex}}"""
        }

        // Build options with high-accuracy overrides
        val optionsJson = buildRetranscribeOptionsJson(progressFile.toString(), autoPrompt, rangesJson)

        // REST_REMOTE mode: send audio over HTTP SSE stream instead of K8s Job / local subprocess
        val settings = whisperSettingsRpc.getSettingsDocument()
        if (settings.deploymentMode == "rest_remote") {
            logger.info { "Using REST remote mode for retranscription: ${settings.restRemoteUrl}" }
            return whisperRestClient.transcribe(
                baseUrl = settings.restRemoteUrl,
                audioFilePath = audioFilePath,
                optionsJson = optionsJson,
                onProgress = buildProgressCallback(meetingId, clientId),
            )
        }

        try {
            if (IN_CLUSTER) {
                runK8sJob(audioFilePath, workspacePath, timeoutSeconds, resultFile, progressFile, optionsJson, meetingId, clientId, modelName = "large-v3")
            } else {
                runLocal(audioFilePath, resultFile, optionsJson)
            }

            return withContext(Dispatchers.IO) {
                if (Files.exists(resultFile)) {
                    val content = Files.readString(resultFile)
                    json.decodeFromString<WhisperResult>(content)
                } else {
                    WhisperResult(text = "", segments = emptyList(), error = "Retranscription completed but no result file found")
                }
            }
        } finally {
            withContext(Dispatchers.IO) {
                Files.deleteIfExists(resultFile)
                Files.deleteIfExists(progressFile)
            }
        }
    }

    /**
     * Build options JSON for retranscription with high-accuracy overrides.
     * Uses large-v3, beam_size=10, lower no_speech_threshold.
     */
    private fun buildRetranscribeOptionsJson(
        progressFilePath: String,
        initialPrompt: String?,
        extractionRangesJson: String,
    ): String {
        val parts = mutableListOf<String>()
        parts.add(""""task":"transcribe"""")
        parts.add(""""model":"large-v3"""")
        parts.add(""""beam_size":10""")
        parts.add(""""vad_filter":true""")
        parts.add(""""word_timestamps":false""")
        parts.add(""""condition_on_previous_text":true""")
        parts.add(""""no_speech_threshold":0.3""")
        parts.add(""""progress_file":"${progressFilePath.replace("\"", "\\\"")}"""")
        parts.add(""""extraction_ranges":$extractionRangesJson""")
        initialPrompt?.let {
            parts.add(""""initial_prompt":"${it.replace("\"", "\\\"")}"""")
        }
        return "{${parts.joinToString(",")}}"
    }

    /**
     * Delete all active Whisper K8s Jobs for a meeting. Best-effort — logs errors but doesn't throw.
     * Returns true if at least one job was deleted.
     */
    suspend fun deleteJobForMeeting(meetingId: String): Boolean {
        if (!IN_CLUSTER) return false
        return withContext(Dispatchers.IO) {
            try {
                buildK8sClient().use { client ->
                    val jobs = client.batch().v1().jobs()
                        .inNamespace(K8S_NAMESPACE)
                        .withLabel("app", "jervis-whisper")
                        .withLabel("meeting-id", meetingId)
                        .list()
                        .items

                    if (jobs.isEmpty()) {
                        logger.info { "No Whisper K8s Jobs found for meeting $meetingId" }
                        return@withContext false
                    }

                    for (job in jobs) {
                        val jobName = job.metadata.name
                        try {
                            client.batch().v1().jobs()
                                .inNamespace(K8S_NAMESPACE)
                                .withName(jobName)
                                .withPropagationPolicy(io.fabric8.kubernetes.api.model.DeletionPropagation.BACKGROUND)
                                .delete()
                            logger.info { "Deleted Whisper K8s Job $jobName for meeting $meetingId" }
                        } catch (e: Exception) {
                            logger.warn(e) { "Failed to delete Whisper K8s Job $jobName" }
                        }
                    }
                    true
                }
            } catch (e: Exception) {
                logger.warn(e) { "Error deleting Whisper K8s Jobs for meeting $meetingId" }
                false
            }
        }
    }

    /**
     * Find an active (non-completed, non-failed) Whisper K8s Job for a specific meeting.
     * Returns the job name if found, null otherwise.
     */
    suspend fun findActiveJobForMeeting(meetingId: String): String? {
        if (!IN_CLUSTER) return null
        return withContext(Dispatchers.IO) {
            buildK8sClient().use { client ->
                val jobs = client.batch().v1().jobs()
                    .inNamespace(K8S_NAMESPACE)
                    .withLabel("app", "jervis-whisper")
                    .withLabel("meeting-id", meetingId)
                    .list()
                    .items

                jobs.firstOrNull { job ->
                    val status = job.status
                    status != null && (status.succeeded ?: 0) == 0 && (status.failed ?: 0) == 0
                }?.metadata?.name
            }
        }
    }

    /**
     * Wait for an existing Whisper K8s Job to complete (used after server restart
     * to re-attach to a job that was already running).
     */
    suspend fun waitForExistingJob(
        jobName: String,
        audioFilePath: String,
        meetingId: String? = null,
        clientId: String? = null,
    ): WhisperResult {
        val audioPath = Paths.get(audioFilePath)
        val resultFileName = audioPath.fileName.toString().replace(".wav", "_transcript.json")
        val resultFile = audioPath.parent.resolve(resultFileName)
        val progressFile = audioPath.parent.resolve(resultFileName.replace("_transcript.json", "_progress.json"))

        val audioFileSize = withContext(Dispatchers.IO) { Files.size(Paths.get(audioFilePath)) }
        val audioDurationSeconds = (audioFileSize - WAV_HEADER_SIZE).coerceAtLeast(0) / BYTES_PER_SECOND
        val settings = whisperSettingsRpc.getSettingsDocument()
        val timeoutSeconds = (audioDurationSeconds * settings.timeoutMultiplier).coerceAtLeast(settings.minTimeoutSeconds.toLong())

        logger.info { "Re-attaching to existing Whisper Job $jobName (timeout: ${timeoutSeconds}s)" }

        withContext(Dispatchers.IO) {
            buildK8sClient().use { client ->
                var elapsed = 0L
                var pollCount = 0

                while (elapsed < timeoutSeconds) {
                    delay(POLL_INTERVAL_SECONDS * 1000)
                    elapsed += POLL_INTERVAL_SECONDS
                    pollCount++

                    val status = client.batch().v1().jobs()
                        .inNamespace(K8S_NAMESPACE)
                        .withName(jobName)
                        .get()
                        ?.status

                    if (status == null) {
                        logger.warn { "Whisper Job $jobName disappeared, treating as failed" }
                        return@withContext
                    }

                    if ((status.succeeded ?: 0) > 0) {
                        logger.info { "Whisper Job $jobName completed successfully after re-attach (${elapsed}s)" }
                        return@withContext
                    }

                    if ((status.failed ?: 0) > 0) {
                        throw RuntimeException("Whisper Job $jobName failed after re-attach (${elapsed}s)")
                    }

                    if (pollCount % PROGRESS_LOG_INTERVAL == 0) {
                        val progressInfo = readProgressFile(progressFile)
                        if (progressInfo != null) {
                            logger.info { "Whisper Job $jobName (re-attached): ${progressInfo.percent}% done" }
                            if (meetingId != null && clientId != null) {
                                notificationRpc.emitMeetingTranscriptionProgress(
                                    meetingId = meetingId, clientId = clientId,
                                    percent = progressInfo.percent, segmentsDone = progressInfo.segmentsDone,
                                    elapsedSeconds = progressInfo.elapsedSeconds,
                                )
                            }
                        }
                    }
                }

                throw RuntimeException("Whisper Job $jobName timed out after re-attach (${timeoutSeconds}s)")
            }
        }

        return withContext(Dispatchers.IO) {
            if (Files.exists(resultFile)) {
                val content = Files.readString(resultFile)
                try {
                    json.decodeFromString<WhisperResult>(content)
                } finally {
                    Files.deleteIfExists(resultFile)
                    Files.deleteIfExists(progressFile)
                }
            } else {
                WhisperResult(text = "", segments = emptyList(), error = "Job completed but no result file found")
            }
        }
    }

    /**
     * Build Whisper initial_prompt from KB correction rules.
     *
     * Fetches corrections from two scopes and merges them:
     * 1. Global client corrections (projectId=null) — always included
     * 2. Project-specific corrections — only if projectId is provided
     *
     * Both "corrected" (correct form) and "original" (misheard form) are included
     * so Whisper knows what to expect and what to avoid.
     */
    private suspend fun buildInitialPromptFromCorrections(clientId: String?, projectId: String?): String? {
        if (clientId == null) return null
        return try {
            // 1. Global client corrections (no project filter)
            val globalResult = orchestratorClient.listCorrections(
                CorrectionListRequestDto(clientId = clientId, projectId = null, maxResults = 500),
            )

            // 2. Project-specific corrections (if project is set)
            val projectResult = if (!projectId.isNullOrBlank()) {
                orchestratorClient.listCorrections(
                    CorrectionListRequestDto(clientId = clientId, projectId = projectId, maxResults = 500),
                )
            } else {
                null
            }

            // Merge all corrections, extract unique terms
            val allCorrections = globalResult.corrections + (projectResult?.corrections ?: emptyList())
            val terms = allCorrections
                .flatMap { listOf(it.metadata.corrected, it.metadata.original) }
                .filter { it.isNotBlank() }
                .distinct()

            if (terms.isEmpty()) {
                logger.info { "No KB corrections found for clientId=$clientId, projectId=$projectId — no initial_prompt" }
                null
            } else {
                val prompt = terms.joinToString(", ")
                logger.info { "Auto-built initial_prompt from ${terms.size} terms (${allCorrections.size} corrections, global+project): ${prompt.take(200)}..." }
                prompt
            }
        } catch (e: Exception) {
            logger.warn(e) { "Failed to fetch KB corrections for initial_prompt (clientId=$clientId, projectId=$projectId), proceeding without" }
            null
        }
    }

    private fun buildOptionsJson(settings: WhisperSettingsDocument, progressFilePath: String, initialPrompt: String? = null): String {
        val opts = mutableMapOf<String, Any?>(
            "task" to settings.task,
            "model" to settings.model,
            "beam_size" to settings.beamSize,
            "vad_filter" to settings.vadFilter,
            "word_timestamps" to settings.wordTimestamps,
            "condition_on_previous_text" to settings.conditionOnPreviousText,
            "no_speech_threshold" to settings.noSpeechThreshold,
            "progress_file" to progressFilePath,
        )
        settings.language?.let { opts["language"] = it }
        initialPrompt?.let { opts["initial_prompt"] = it }

        // Manual JSON serialization to avoid dependency issues
        return buildString {
            append("{")
            val entries = opts.entries.toList()
            entries.forEachIndexed { index, (key, value) ->
                append("\"$key\":")
                when (value) {
                    is String -> append("\"${value.replace("\"", "\\\"")}\"")
                    is Boolean -> append(value)
                    is Number -> append(value)
                    null -> append("null")
                    else -> append("\"$value\"")
                }
                if (index < entries.size - 1) append(",")
            }
            append("}")
        }
    }

    /**
     * Returns (memoryRequest, memoryLimit) for a given Whisper model.
     * Larger models need more RAM to avoid OOM kills.
     */
    private fun resourcesForModel(modelName: String): Pair<String, String> =
        when (modelName) {
            "tiny", "base" -> "512Mi" to "2Gi"
            "small"        -> "1Gi" to "3Gi"
            "medium"       -> "2Gi" to "6Gi"
            "large-v3"     -> "4Gi" to "12Gi"
            else           -> "512Mi" to "2Gi"
        }

    private suspend fun runK8sJob(
        audioFilePath: String,
        workspacePath: String,
        timeoutSeconds: Long,
        resultFile: Path,
        progressFile: Path,
        optionsJson: String,
        meetingId: String? = null,
        clientId: String? = null,
        modelName: String = "base",
    ) {
        val jobName = "whisper-${UUID.randomUUID().toString().substring(0, 8)}"

        val (memReq, memLimit) = resourcesForModel(modelName)
        logger.info { "Creating Whisper K8s Job: $jobName (audio=$audioFilePath, workspace=$workspacePath, model=$modelName, mem=$memReq/$memLimit)" }

        val job = JobBuilder()
            .withNewMetadata()
                .withName(jobName)
                .withNamespace(K8S_NAMESPACE)
                .addToLabels("app", "jervis-whisper")
                .addToLabels("type", "job")
                .apply { meetingId?.let { addToLabels("meeting-id", it) } }
            .endMetadata()
            .withNewSpec()
                .withTtlSecondsAfterFinished(300)
                .withBackoffLimit(0)
                .withNewTemplate()
                    .withNewMetadata()
                        .addToLabels("app", "jervis-whisper")
                        .addToLabels("type", "job")
                    .endMetadata()
                    .withNewSpec()
                        .withRestartPolicy("Never")
                        .addNewContainer()
                            .withName("whisper")
                            .withImage(WHISPER_IMAGE)
                            .withImagePullPolicy("Always")
                            .withEnv(
                                EnvVarBuilder().withName("WORKSPACE").withValue(workspacePath).build(),
                                EnvVarBuilder().withName("AUDIO_FILE").withValue(audioFilePath).build(),
                                EnvVarBuilder().withName("RESULT_FILE").withValue(resultFile.toString()).build(),
                                EnvVarBuilder().withName("PROGRESS_FILE").withValue(progressFile.toString()).build(),
                                EnvVarBuilder().withName("WHISPER_OPTIONS").withValue(optionsJson).build(),
                            )
                            .withVolumeMounts(
                                VolumeMountBuilder()
                                    .withName("data")
                                    .withMountPath(PVC_MOUNT)
                                    .build(),
                            )
                            .withNewResources()
                                .addToRequests("memory", io.fabric8.kubernetes.api.model.Quantity(memReq))
                                .addToRequests("cpu", io.fabric8.kubernetes.api.model.Quantity("500m"))
                                .addToLimits("memory", io.fabric8.kubernetes.api.model.Quantity(memLimit))
                                .addToLimits("cpu", io.fabric8.kubernetes.api.model.Quantity("2"))
                            .endResources()
                        .endContainer()
                        .withVolumes(
                            VolumeBuilder()
                                .withName("data")
                                .withPersistentVolumeClaim(
                                    PersistentVolumeClaimVolumeSourceBuilder()
                                        .withClaimName(PVC_NAME)
                                        .build(),
                                )
                                .build(),
                        )
                    .endSpec()
                .endTemplate()
            .endSpec()
            .build()

        withContext(Dispatchers.IO) {
            buildK8sClient().use { client ->
                client.batch().v1().jobs()
                    .inNamespace(K8S_NAMESPACE)
                    .resource(job)
                    .create()

                // Poll for completion with progress logging from progress file
                var elapsed = 0L
                var pollCount = 0
                logger.info { "Waiting for Whisper Job $jobName (timeout: ${timeoutSeconds}s, poll interval: ${POLL_INTERVAL_SECONDS}s)" }

                while (elapsed < timeoutSeconds) {
                    delay(POLL_INTERVAL_SECONDS * 1000)
                    elapsed += POLL_INTERVAL_SECONDS
                    pollCount++

                    val status = client.batch().v1().jobs()
                        .inNamespace(K8S_NAMESPACE)
                        .withName(jobName)
                        .get()
                        ?.status

                    if (status == null) {
                        logger.warn { "Whisper Job $jobName status is null, retrying..." }
                        continue
                    }

                    if ((status.succeeded ?: 0) > 0) {
                        logger.info { "Whisper Job $jobName completed successfully after ${elapsed}s" }
                        return@withContext
                    }

                    if ((status.failed ?: 0) > 0) {
                        throw RuntimeException("Whisper Job $jobName failed after ${elapsed}s")
                    }

                    // Read progress file from PVC and emit progress events
                    if (pollCount % PROGRESS_LOG_INTERVAL == 0) {
                        val progressInfo = readProgressFile(progressFile)
                        val remaining = timeoutSeconds - elapsed
                        if (progressInfo != null) {
                            logger.info {
                                "Whisper Job $jobName: ${progressInfo.percent}% done " +
                                    "(${progressInfo.segmentsDone} segments, ${progressInfo.elapsedSeconds.toLong()}s whisper time, " +
                                    "${elapsed}s wall time, ${remaining}s timeout remaining)"
                            }
                            if (meetingId != null && clientId != null) {
                                notificationRpc.emitMeetingTranscriptionProgress(
                                    meetingId = meetingId,
                                    clientId = clientId,
                                    percent = progressInfo.percent,
                                    segmentsDone = progressInfo.segmentsDone,
                                    elapsedSeconds = progressInfo.elapsedSeconds,
                                )
                            }
                        } else {
                            logger.info {
                                "Whisper Job $jobName still running (${elapsed}s elapsed, ${remaining}s remaining, " +
                                    "active=${status.active ?: 0})"
                            }
                        }
                    }
                }

                throw RuntimeException("Whisper Job $jobName timed out after ${timeoutSeconds}s")
            }
        }
    }

    private fun readProgressFile(progressFile: Path): WhisperProgress? {
        return try {
            if (Files.exists(progressFile)) {
                val content = Files.readString(progressFile)
                json.decodeFromString<WhisperProgress>(content)
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Build a progress callback for REST mode that emits transcription progress
     * notifications via the same notification system as K8s Job polling.
     */
    private fun buildProgressCallback(
        meetingId: String?,
        clientId: String?,
    ): (suspend (Double, Int, Double, String?) -> Unit)? {
        if (meetingId == null || clientId == null) return null
        return { percent, segmentsDone, elapsedSeconds, lastSegmentText ->
            logger.info { "Whisper REST progress: $percent% ($segmentsDone segments, ${elapsedSeconds.toLong()}s)" }
            notificationRpc.emitMeetingTranscriptionProgress(
                meetingId = meetingId,
                clientId = clientId,
                percent = percent,
                segmentsDone = segmentsDone,
                elapsedSeconds = elapsedSeconds,
                lastSegmentText = lastSegmentText,
            )
        }
    }

    private suspend fun runLocal(audioFilePath: String, resultFile: Path, optionsJson: String) {
        withContext(Dispatchers.IO) {
            val whisperScript = findWhisperRunner()
            val cmd = listOf("python3", whisperScript, audioFilePath, optionsJson)

            logger.info { "Running Whisper locally: python3 $whisperScript $audioFilePath <options>" }

            val process = ProcessBuilder(cmd)
                .redirectErrorStream(false)
                .start()

            val stdout = process.inputStream.bufferedReader().readText()
            val stderr = process.errorStream.bufferedReader().readText()
            val exitCode = process.waitFor()

            if (stderr.isNotBlank()) {
                logger.info { "Whisper stderr: $stderr" }
            }

            if (exitCode != 0) {
                logger.error { "Whisper process failed (exit=$exitCode): $stderr" }
                Files.writeString(
                    resultFile,
                    """{"text": "", "segments": [], "error": "Whisper failed (exit=$exitCode): $stderr"}""",
                )
            } else {
                Files.writeString(resultFile, stdout)
            }
        }
    }

    private fun findWhisperRunner(): String {
        val candidates = listOf(
            "backend/service-whisper/whisper_runner.py",
            "../service-whisper/whisper_runner.py",
            "/opt/jervis/whisper/whisper_runner.py",
        )
        for (candidate in candidates) {
            if (Paths.get(candidate).toFile().exists()) return candidate
        }
        return "whisper_runner.py"
    }
}

@Serializable
data class WhisperResult(
    val text: String,
    val segments: List<WhisperSegment> = emptyList(),
    val error: String? = null,
    val language: String? = null,
    val languageProbability: Double? = null,
    val duration: Double? = null,
    /** Retranscription mode: segment_index → re-transcribed text */
    @SerialName("text_by_segment")
    val textBySegment: Map<String, String> = emptyMap(),
)

@Serializable
data class WhisperSegment(
    val start: Double,
    val end: Double,
    val text: String,
)

@Serializable
data class WhisperProgress(
    val percent: Double = 0.0,
    val segmentsDone: Int = 0,
    val elapsedSeconds: Double = 0.0,
    val updatedAt: Double = 0.0,
)

/** Time range for audio extraction (retranscription). */
data class ExtractionRange(
    val start: Double,
    val end: Double,
    val segmentIndex: Int,
)
