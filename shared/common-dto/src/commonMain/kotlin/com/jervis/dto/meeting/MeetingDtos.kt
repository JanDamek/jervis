package com.jervis.dto.meeting

import kotlinx.serialization.Serializable

@Serializable
enum class MeetingTypeEnum {
    MEETING,
    TASK_DISCUSSION,
    STANDUP_PROJECT,
    STANDUP_TEAM,
    INTERVIEW,
    WORKSHOP,
    REVIEW,
    OTHER,
}

@Serializable
enum class MeetingStateEnum {
    RECORDING,
    UPLOADING,
    UPLOADED,
    TRANSCRIBING,
    TRANSCRIBED,
    CORRECTING,
    CORRECTION_REVIEW,
    CORRECTED,
    INDEXED,
    FAILED,
}

@Serializable
enum class AudioInputType {
    MICROPHONE,
    SYSTEM_AUDIO,
    MIXED,
}

@Serializable
data class MeetingDto(
    val id: String,
    val clientId: String,
    val projectId: String? = null,
    val title: String? = null,
    val meetingType: MeetingTypeEnum? = null,
    val audioInputType: AudioInputType = AudioInputType.MIXED,
    val state: MeetingStateEnum,
    val durationSeconds: Long? = null,
    val startedAt: String,
    val stoppedAt: String? = null,
    val transcriptText: String? = null,
    val transcriptSegments: List<TranscriptSegmentDto> = emptyList(),
    val correctedTranscriptText: String? = null,
    val correctedTranscriptSegments: List<TranscriptSegmentDto> = emptyList(),
    val correctionQuestions: List<CorrectionQuestionDto> = emptyList(),
    val errorMessage: String? = null,
    val deleted: Boolean = false,
    val deletedAt: String? = null,
)

@Serializable
data class CorrectionQuestionDto(
    val questionId: String,
    val segmentIndex: Int,
    val originalText: String,
    val correctionOptions: List<String> = emptyList(),
    val question: String,
    val context: String? = null,
)

@Serializable
data class CorrectionAnswerDto(
    val questionId: String,
    val segmentIndex: Int,
    val original: String,
    val corrected: String,
    val category: String = "general",
)

@Serializable
data class TranscriptSegmentDto(
    val startSec: Double,
    val endSec: Double,
    val text: String,
    val speaker: String? = null,
)

@Serializable
data class MeetingCreateDto(
    val clientId: String,
    val projectId: String? = null,
    val audioInputType: AudioInputType = AudioInputType.MIXED,
)

@Serializable
data class MeetingFinalizeDto(
    val meetingId: String,
    val title: String? = null,
    val meetingType: MeetingTypeEnum,
    val durationSeconds: Long,
)

@Serializable
data class AudioChunkDto(
    val meetingId: String,
    val chunkIndex: Int,
    val data: String,
    val mimeType: String = "audio/wav",
    val isLast: Boolean = false,
)
