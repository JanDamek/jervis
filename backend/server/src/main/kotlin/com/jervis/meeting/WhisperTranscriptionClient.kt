package com.jervis.meeting

import com.jervis.contracts.whisper.TranscribeOptions
import com.jervis.infrastructure.config.properties.WhisperProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import mu.KotlinLogging
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Paths

private val logger = KotlinLogging.logger {}

/**
 * Whisper transcription client — dials the gRPC server on the Ollama VM.
 *
 * Audio bytes ride inline inside `TranscribeRequest` (up to 256 MiB);
 * progress + result events stream back as `TranscribeEvent` oneofs.
 * The class name kept its legacy "Rest" suffix purely so the Spring
 * bean graph + call sites don't churn — the wire format has been gRPC
 * since commit 95c8f1d1a. Server at ollama.lan.mazlusek.com:5502
 * (deployed via deploy_whisper_gpu.sh).
 */
@Service
class WhisperTranscriptionClient(
    private val whisperProperties: WhisperProperties,
    private val notificationRpc: com.jervis.rpc.NotificationRpcImpl,
    private val correctionClient: com.jervis.infrastructure.llm.CorrectionClient,
    private val whisperRestClient: WhisperRestClient,
    private val urgencyDetector: MeetingUrgencyDetector,
) {

    companion object {
        private const val WAV_HEADER_SIZE = 44
        private const val BYTES_PER_SECOND = 32_000 // 16kHz, 16-bit, mono
    }

    /**
     * Check if Whisper transcription is available (REST health check).
     */
    suspend fun isAvailable(): Boolean =
        whisperRestClient.isHealthy(whisperProperties.restRemoteUrl)

    /**
     * Transcribe an audio file using Whisper via REST.
     *
     * @param audioFilePath Absolute path to the audio file
     * @param meetingId Meeting ID for progress notifications
     * @param clientId Client ID for KB corrections lookup
     * @param projectId Project ID for KB corrections lookup
     * @return WhisperResult with transcription text, segments, and speaker labels
     */
    suspend fun transcribe(
        audioFilePath: String,
        meetingId: String?,
        clientId: String?,
        projectId: String?,
        diarize: Boolean = whisperProperties.diarize,
    ): WhisperResult {
        val settings = whisperProperties

        val autoPrompt = buildInitialPromptFromCorrections(clientId, projectId)

        val audioFileSize = withContext(Dispatchers.IO) { Files.size(Paths.get(audioFilePath)) }
        val audioDurationSeconds = (audioFileSize - WAV_HEADER_SIZE).coerceAtLeast(0) / BYTES_PER_SECOND
        logger.info {
            "Whisper transcription: audio=${audioDurationSeconds}s, file=${audioFileSize} bytes, " +
                "model=${settings.model}, beam=${settings.beamSize}, diarize=$diarize"
        }

        val options = buildTranscribeOptions(settings, autoPrompt, diarize)

        return whisperRestClient.transcribe(
            baseUrl = settings.restRemoteUrl,
            audioFilePath = audioFilePath,
            options = options,
            onProgress = buildProgressCallback(meetingId, clientId),
        )
    }

    /**
     * Re-transcribe specific audio ranges with high-accuracy settings.
     *
     * Used for "Nevim" (I don't know) answers: extracts audio around unclear segments,
     * re-transcribes with large-v3 + beam_size=10 for best accuracy.
     *
     * @param audioFilePath Source audio file
     * @param extractionRanges Time ranges to extract: [{start, end, segment_index}, ...]
     * @return WhisperResult with text_by_segment mapping segment indices to re-transcribed text
     */
    suspend fun retranscribe(
        audioFilePath: String,
        extractionRanges: List<ExtractionRange>,
        meetingId: String?,
        clientId: String?,
        projectId: String?,
    ): WhisperResult {
        val autoPrompt = buildInitialPromptFromCorrections(clientId, projectId)

        val totalExtractedSeconds = extractionRanges.sumOf { it.end - it.start }.toLong()
        logger.info {
            "Retranscription: ${extractionRanges.size} ranges, ${totalExtractedSeconds}s extracted audio"
        }

        val options = buildRetranscribeOptions(autoPrompt, extractionRanges)

        return whisperRestClient.transcribe(
            baseUrl = whisperProperties.restRemoteUrl,
            audioFilePath = audioFilePath,
            options = options,
            onProgress = buildProgressCallback(meetingId, clientId),
        )
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
            val globalResult = correctionClient.listCorrections(
                clientId = clientId,
                projectId = null,
                maxResults = 500,
            )

            val projectResult = if (!projectId.isNullOrBlank()) {
                correctionClient.listCorrections(
                    clientId = clientId,
                    projectId = projectId,
                    maxResults = 500,
                )
            } else {
                null
            }

            val allCorrections = globalResult.correctionsList + (projectResult?.correctionsList ?: emptyList())
            val terms = allCorrections
                .flatMap { listOf(it.metadata.corrected, it.metadata.original) }
                .filter { it.isNotBlank() }
                .distinct()

            if (terms.isEmpty()) {
                logger.info { "No KB corrections found for clientId=$clientId, projectId=$projectId — no initial_prompt" }
                null
            } else {
                val prompt = terms.joinToString(", ")
                logger.info { "Auto-built initial_prompt from ${terms.size} terms (${allCorrections.size} corrections): ${prompt.take(200)}..." }
                prompt
            }
        } catch (e: Exception) {
            logger.warn(e) { "Failed to fetch KB corrections for initial_prompt (clientId=$clientId, projectId=$projectId), proceeding without" }
            null
        }
    }

    private fun buildTranscribeOptions(
        settings: WhisperProperties,
        initialPrompt: String? = null,
        diarize: Boolean = settings.diarize,
    ): TranscribeOptions =
        TranscribeOptions.newBuilder()
            .setTask("transcribe")
            .setModel(settings.model)
            .setBeamSize(settings.beamSize)
            .setVadFilter(settings.vadFilter)
            .setWordTimestamps(false)
            .setConditionOnPreviousText(settings.conditionOnPreviousText)
            .setNoSpeechThreshold(settings.noSpeechThreshold)
            .setDiarize(diarize)
            .setInitialPrompt(initialPrompt ?: "")
            .build()

    /**
     * Build options for retranscription with high-accuracy overrides.
     */
    private fun buildRetranscribeOptions(
        initialPrompt: String?,
        extractionRanges: List<ExtractionRange>,
    ): TranscribeOptions {
        val builder = TranscribeOptions.newBuilder()
            .setTask("transcribe")
            .setModel("large-v3")
            .setBeamSize(10)
            .setVadFilter(true)
            .setWordTimestamps(false)
            .setConditionOnPreviousText(true)
            .setNoSpeechThreshold(0.3)
            .setDiarize(false)
            .setInitialPrompt(initialPrompt ?: "")
        for (range in extractionRanges) {
            builder.addExtractionRanges(
                com.jervis.contracts.whisper.ExtractionRange.newBuilder()
                    .setStartSec(range.start)
                    .setEndSec(range.end)
                    .setSegmentIndex(range.segmentIndex)
                    .build(),
            )
        }
        return builder.build()
    }

    /**
     * Build a progress callback for REST mode that emits transcription progress
     * notifications via the notification system.
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
            // Live urgency detection — runs ONLY when a fresh segment text is
            // available; never throws into the progress hot path. The detector
            // applies its own per-meeting cooldown to avoid notification floods.
            runCatching {
                urgencyDetector.analyzeSegment(
                    meetingId = meetingId,
                    clientId = clientId,
                    segmentText = lastSegmentText,
                )
            }.onFailure { e ->
                logger.warn { "MeetingUrgencyDetector.analyzeSegment failed: ${e.message}" }
            }
        }
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
    /** Speaker labels from diarization */
    val speakers: List<String>? = null,
    /** Speaker voice embeddings from pyannote (256-dim float per speaker label) */
    @SerialName("speaker_embeddings")
    val speakerEmbeddings: Map<String, List<Float>>? = null,
)

@Serializable
data class WhisperSegment(
    val start: Double,
    val end: Double,
    val text: String,
    val speaker: String? = null,
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
