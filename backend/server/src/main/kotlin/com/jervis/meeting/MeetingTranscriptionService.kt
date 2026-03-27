package com.jervis.meeting

import com.jervis.dto.meeting.MeetingStateEnum
import kotlinx.coroutines.flow.toList
import mu.KotlinLogging
import org.springframework.stereotype.Service
import java.time.Instant
import kotlin.math.sqrt

private val logger = KotlinLogging.logger {}

/**
 * Orchestrates Whisper transcription for uploaded meeting recordings.
 *
 * Called by MeetingContinuousIndexer when a meeting reaches UPLOADED state.
 * Uses WhisperJobRunner (REST remote).
 */
@Service
class MeetingTranscriptionService(
    private val meetingRepository: MeetingRepository,
    private val speakerRepository: SpeakerRepository,
    private val whisperJobRunner: WhisperJobRunner,
    private val notificationRpc: com.jervis.rpc.NotificationRpcImpl,
) {

    /**
     * Transcribe a meeting's audio file using Whisper.
     *
     * @param meeting MeetingDocument in UPLOADED state
     * @return Updated MeetingDocument in TRANSCRIBED or FAILED state
     */
    suspend fun transcribe(meeting: MeetingDocument): MeetingDocument {
        require(meeting.state == MeetingStateEnum.UPLOADED) {
            "Can only transcribe UPLOADED meetings, got: ${meeting.state}"
        }
        require(!meeting.audioFilePath.isNullOrBlank()) {
            "Meeting ${meeting.id} has no audio file path"
        }

        logger.info { "Starting transcription for meeting ${meeting.id} (audio: ${meeting.audioFilePath})" }

        val meetingIdStr = meeting.id.toHexString()
        val clientIdStr = meeting.clientId?.toString().orEmpty()

        // Mark as TRANSCRIBING
        val transcribing = meetingRepository.save(meeting.copy(state = MeetingStateEnum.TRANSCRIBING, stateChangedAt = Instant.now()))
        notificationRpc.emitMeetingStateChanged(meetingIdStr, clientIdStr, MeetingStateEnum.TRANSCRIBING.name, meeting.title)

        try {
            val result = whisperJobRunner.transcribe(
                audioFilePath = meeting.audioFilePath,
                meetingId = meetingIdStr,
                clientId = clientIdStr,
                projectId = meeting.projectId?.toString(),
            )

            if (!result.error.isNullOrBlank()) {
                logger.error { "Whisper returned error for meeting ${meeting.id}: ${result.error}" }
                // Re-read from DB — state may have been changed by stopTranscription()
                val current = meetingRepository.findById(meeting.id) ?: return transcribing
                if (current.state != MeetingStateEnum.TRANSCRIBING) {
                    logger.info { "Meeting ${meeting.id} state changed to ${current.state} during transcription, skipping FAILED transition" }
                    return current
                }
                val failed = meetingRepository.save(
                    current.copy(
                        state = MeetingStateEnum.FAILED,
                        stateChangedAt = Instant.now(),
                        errorMessage = "Whisper error: ${result.error}",
                    ),
                )
                notificationRpc.emitMeetingStateChanged(meetingIdStr, clientIdStr, MeetingStateEnum.FAILED.name, meeting.title, result.error)
                return failed
            }

            val segments = result.segments.map { seg ->
                TranscriptSegment(
                    startSec = seg.start,
                    endSec = seg.end,
                    text = seg.text.trim(),
                    speaker = seg.speaker,
                )
            }

            // Re-read from DB — user may have classified the meeting (set clientId/projectId)
            // during the long-running transcription. Using stale `transcribing` would overwrite those fields.
            val current = meetingRepository.findById(meeting.id) ?: transcribing

            // Auto-match speakers using voice embeddings
            val autoMapping = if (!result.speakerEmbeddings.isNullOrEmpty() && current.clientId != null) {
                autoMatchSpeakers(result.speakerEmbeddings, current.clientId)
            } else {
                emptyMap()
            }

            val transcribed = meetingRepository.save(
                current.copy(
                    state = MeetingStateEnum.TRANSCRIBED,
                    stateChangedAt = Instant.now(),
                    transcriptText = result.text,
                    transcriptSegments = segments,
                    speakerEmbeddings = result.speakerEmbeddings,
                    speakerMapping = if (autoMapping.isNotEmpty()) autoMapping else current.speakerMapping,
                ),
            )
            val currentClientId = current.clientId?.toString().orEmpty()
            notificationRpc.emitMeetingStateChanged(meetingIdStr, currentClientId, MeetingStateEnum.TRANSCRIBED.name, current.title)

            if (autoMapping.isNotEmpty()) {
                logger.info { "Auto-matched ${autoMapping.size} speakers for meeting ${meeting.id}" }
            }
            logger.info {
                "Transcription complete for meeting ${meeting.id}: " +
                    "${result.text.length} chars, ${segments.size} segments" +
                    (result.speakerEmbeddings?.let { ", ${it.size} speaker embeddings" } ?: "")
            }

            return transcribed
        } catch (e: Exception) {
            logger.error(e) { "Transcription failed for meeting ${meeting.id}" }
            // Re-read from DB — state may have been changed by stopTranscription()
            val current = meetingRepository.findById(meeting.id) ?: return transcribing
            if (current.state != MeetingStateEnum.TRANSCRIBING) {
                logger.info { "Meeting ${meeting.id} state changed to ${current.state} during transcription, skipping FAILED transition" }
                return current
            }
            val failed = meetingRepository.save(
                current.copy(
                    state = MeetingStateEnum.FAILED,
                    stateChangedAt = Instant.now(),
                    errorMessage = "Transcription error: ${e.message}",
                ),
            )
            notificationRpc.emitMeetingStateChanged(meetingIdStr, clientIdStr, MeetingStateEnum.FAILED.name, meeting.title, e.message)
            return failed
        }
    }

    /**
     * Auto-match speaker embeddings against known speakers for a client.
     * Returns a mapping of diarization label → speakerId for matches above threshold.
     */
    private suspend fun autoMatchSpeakers(
        embeddings: Map<String, List<Float>>,
        clientId: com.jervis.common.types.ClientId,
    ): Map<String, String> {
        val knownSpeakers = speakerRepository.findByClientIdsContainingOrderByNameAsc(clientId)
            .toList()
            .filter { it.allEmbeddings().isNotEmpty() }

        if (knownSpeakers.isEmpty()) return emptyMap()

        val mapping = mutableMapOf<String, String>()
        val usedSpeakers = mutableSetOf<String>() // prevent same speaker matched twice

        for ((label, embedding) in embeddings) {
            var bestSpeaker: SpeakerDocument? = null
            var bestSim = 0f
            var bestLabel: String? = null

            for (speaker in knownSpeakers) {
                if (speaker.id.toHexString() in usedSpeakers) continue
                // Search across ALL embeddings for this speaker (different conditions)
                for (entry in speaker.allEmbeddings()) {
                    val sim = cosineSimilarity(embedding, entry.embedding)
                    if (sim > bestSim) {
                        bestSim = sim
                        bestSpeaker = speaker
                        bestLabel = entry.label
                    }
                }
            }

            if (bestSpeaker != null && bestSim > SPEAKER_MATCH_THRESHOLD) {
                val speakerId = bestSpeaker.id.toHexString()
                mapping[label] = speakerId
                usedSpeakers.add(speakerId)
                logger.info { "Auto-matched $label → ${bestSpeaker.name} (similarity=${bestSim}, embedding=${bestLabel ?: "unknown"})" }
            }
        }

        return mapping
    }

    companion object {
        /** Minimum cosine similarity for automatic speaker identification */
        const val SPEAKER_MATCH_THRESHOLD = 0.70f
    }
}

private fun cosineSimilarity(a: List<Float>, b: List<Float>): Float {
    require(a.size == b.size) { "Embedding dimensions must match: ${a.size} vs ${b.size}" }
    var dot = 0f
    var normA = 0f
    var normB = 0f
    for (i in a.indices) {
        dot += a[i] * b[i]
        normA += a[i] * a[i]
        normB += b[i] * b[i]
    }
    val denom = sqrt(normA) * sqrt(normB)
    return if (denom > 0f) dot / denom else 0f
}
