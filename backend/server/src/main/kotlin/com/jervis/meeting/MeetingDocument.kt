package com.jervis.meeting

import com.jervis.common.types.ClientId
import com.jervis.common.types.ProjectGroupId
import com.jervis.common.types.ProjectId
import com.jervis.dto.meeting.AudioInputType
import com.jervis.dto.meeting.MeetingStateEnum
import com.jervis.dto.meeting.MeetingTypeEnum
import org.bson.types.ObjectId
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.index.CompoundIndex
import org.springframework.data.mongodb.core.index.CompoundIndexes
import org.springframework.data.mongodb.core.mapping.Document
import java.time.Instant

/**
 * Meeting recording document.
 *
 * STATE MACHINE: RECORDING -> UPLOADED -> TRANSCRIBING -> TRANSCRIBED -> INDEXED
 *   -> (after qualification) CORRECTING -> CORRECTED (or CORRECTION_REVIEW) -> re-indexed
 *   (or FAILED at any step)
 *
 * FLOW:
 * 1. Client calls startRecording -> creates RECORDING document + empty audio file on PVC
 * 2. Client uploads audio chunks via uploadAudioChunk -> appended to file on disk
 * 3. Client calls finalizeRecording -> state becomes UPLOADED, metadata set
 * 4. MeetingContinuousIndexer picks up UPLOADED -> sends to Whisper GPU REST -> TRANSCRIBED
 * 5. MeetingContinuousIndexer indexes raw transcript -> INDEXED (creates MEETING_PROCESSING task)
 * 6. After qualification (client/project known, qualified=true) -> LLM correction -> CORRECTED
 * 7. Re-indexing with corrected text
 */
@Document(collection = "meetings")
@CompoundIndexes(
    CompoundIndex(name = "client_state_idx", def = "{'clientId': 1, 'state': 1}"),
    CompoundIndex(name = "project_state_idx", def = "{'projectId': 1, 'state': 1}"),
    CompoundIndex(name = "client_started_idx", def = "{'clientId': 1, 'startedAt': -1}"),
    CompoundIndex(name = "dedup_idx", def = "{'clientId': 1, 'meetingType': 1, 'state': 1, 'deleted': 1}"),
)
data class MeetingDocument(
    @Id
    val id: ObjectId = ObjectId.get(),
    val clientId: ClientId? = null,
    val projectId: ProjectId? = null,
    val groupId: ProjectGroupId? = null,
    val title: String? = null,
    val meetingType: MeetingTypeEnum? = null,
    val audioInputType: AudioInputType = AudioInputType.MIXED,
    val state: MeetingStateEnum = MeetingStateEnum.RECORDING,
    val audioFilePath: String? = null,
    val audioMimeType: String = "audio/webm",
    val audioSizeBytes: Long = 0,
    val durationSeconds: Long? = null,
    val startedAt: Instant = Instant.now(),
    val stoppedAt: Instant? = null,
    val transcriptText: String? = null,
    val transcriptSegments: List<TranscriptSegment> = emptyList(),
    val correctedTranscriptText: String? = null,
    val correctedTranscriptSegments: List<TranscriptSegment> = emptyList(),
    val correctionQuestions: List<CorrectionQuestion> = emptyList(),
    val stateChangedAt: Instant? = null,
    val errorMessage: String? = null,
    val chunkCount: Int = 0,
    val correctionChatHistory: List<CorrectionChatMessage> = emptyList(),
    val speakerMapping: Map<String, String> = emptyMap(),
    /** Speaker voice embeddings from pyannote diarization (label → 256-dim float vector) */
    val speakerEmbeddings: Map<String, List<Float>>? = null,
    val qualified: Boolean = false,
    val deleted: Boolean = false,
    val deletedAt: Instant? = null,
    /** True when meeting completed full pipeline: transcribed + corrected + re-indexed. */
    val fullyProcessed: Boolean = false,
    /** Device session ID for multi-device deduplication (future: merge recordings from multiple devices). */
    val deviceSessionId: String? = null,

    // ── Pod-recorded meetings (product §10a) ──────────────────────────
    /** True when the agent auto-joined via /instruction/ (vs user-joined via VNC). */
    val joinedByAgent: Boolean = false,
    /** Number of WebM chunks received so far — grows during RECORDING. */
    val chunksReceived: Int = 0,
    /** Timestamp of most recent accepted video-chunk POST. */
    val lastChunkAt: Instant? = null,
    /** Filesystem path of the assembled WebM on the server (set at FINALIZING). */
    val webmPath: String? = null,
    /** Retention cutoff — cleanup job drops the WebM after this, keeps metadata + transcript. */
    val videoRetentionUntil: Instant? = null,
    /** Timeline entries assembled during INDEXING — `{ts, diarizedSegment?, frameThumbPath?, frameDescription?}`. */
    val timeline: List<MeetingTimelineEntry> = emptyList(),
)

/** One point on the meeting timeline — either a diarized speech segment or a scene-change frame. */
data class MeetingTimelineEntry(
    val tsSec: Double,
    val diarizedText: String? = null,
    val diarizedSpeaker: String? = null,
    val frameThumbPath: String? = null,
    val frameDescription: String? = null,
)

data class CorrectionChatMessage(
    val role: String,
    val text: String,
    val timestamp: Instant = Instant.now(),
    val rulesCreated: Int = 0,
    val status: String = "success",
)

data class CorrectionQuestion(
    val questionId: String,
    val segmentIndex: Int,
    val originalText: String,
    val correctionOptions: List<String> = emptyList(),
    val question: String,
    val context: String? = null,
)

data class TranscriptSegment(
    val startSec: Double,
    val endSec: Double,
    val text: String,
    val speaker: String? = null,
)
