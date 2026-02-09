package com.jervis.entity.meeting

import com.jervis.common.types.ClientId
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
 * STATE MACHINE: RECORDING -> UPLOADED -> TRANSCRIBING -> TRANSCRIBED -> CORRECTING -> CORRECTED (or CORRECTION_REVIEW if questions) -> INDEXED (or FAILED at any step)
 *
 * FLOW:
 * 1. Client calls startRecording -> creates RECORDING document + empty audio file on PVC
 * 2. Client uploads audio chunks via uploadAudioChunk -> appended to file on disk
 * 3. Client calls finalizeRecording -> state becomes UPLOADED, metadata set
 * 4. MeetingContinuousIndexer picks up UPLOADED -> runs Whisper K8s Job -> TRANSCRIBED
 * 5. MeetingContinuousIndexer creates PendingTask for KB ingest -> INDEXED
 */
@Document(collection = "meetings")
@CompoundIndexes(
    CompoundIndex(name = "client_state_idx", def = "{'clientId': 1, 'state': 1}"),
    CompoundIndex(name = "project_state_idx", def = "{'projectId': 1, 'state': 1}"),
    CompoundIndex(name = "client_started_idx", def = "{'clientId': 1, 'startedAt': -1}"),
)
data class MeetingDocument(
    @Id
    val id: ObjectId = ObjectId.get(),
    val clientId: ClientId,
    val projectId: ProjectId? = null,
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
    val errorMessage: String? = null,
    val chunkCount: Int = 0,
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
