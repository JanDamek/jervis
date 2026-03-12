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
    AD_HOC,
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
    val clientId: String? = null,
    val projectId: String? = null,
    val groupId: String? = null,
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
    val stateChangedAt: String? = null,
    val errorMessage: String? = null,
    val correctionChatHistory: List<CorrectionChatMessageDto> = emptyList(),
    val speakerMapping: Map<String, String> = emptyMap(),
    /** Speaker voice embeddings from diarization (label → 256-dim float vector) */
    val speakerEmbeddings: Map<String, List<Float>>? = null,
    /** Auto-matched speakers based on voice embedding similarity */
    val autoSpeakerMapping: Map<String, AutoSpeakerMatchDto>? = null,
    val deleted: Boolean = false,
    val deletedAt: String? = null,
    /** Merge suggestion after classify/edit — other meeting with same key + overlapping time. */
    val mergeSuggestion: MergeSuggestionDto? = null,
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
data class AutoSpeakerMatchDto(
    val speakerId: String,
    val speakerName: String,
    val confidence: Float,
    /** Which voice embedding matched (label from VoiceEmbeddingEntry) */
    val matchedEmbeddingLabel: String? = null,
)

@Serializable
data class TranscriptSegmentDto(
    val startSec: Double,
    val endSec: Double,
    val text: String,
    val speaker: String? = null,
    val speakerName: String? = null,
    val speakerId: String? = null,
)

@Serializable
data class MeetingCreateDto(
    val clientId: String? = null,
    val projectId: String? = null,
    val audioInputType: AudioInputType = AudioInputType.MIXED,
    val title: String? = null,
    val meetingType: MeetingTypeEnum? = null,
    /** Device session ID for multi-device deduplication (future). */
    val deviceSessionId: String? = null,
)

@Serializable
data class MeetingClassifyDto(
    val meetingId: String,
    val clientId: String,
    val projectId: String? = null,
    val groupId: String? = null,
    val title: String? = null,
    val meetingType: MeetingTypeEnum? = null,
)

@Serializable
data class MeetingFinalizeDto(
    val meetingId: String,
    val title: String? = null,
    val meetingType: MeetingTypeEnum,
    val durationSeconds: Long,
)

@Serializable
enum class CorrectionChatRole {
    @kotlinx.serialization.SerialName("user") USER,
    @kotlinx.serialization.SerialName("agent") AGENT,
}

@Serializable
data class CorrectionChatMessageDto(
    val role: CorrectionChatRole,
    val text: String,
    val timestamp: String,         // ISO 8601
    val rulesCreated: Int = 0,
    val status: String = "success",
)

@Serializable
data class AudioChunkDto(
    val meetingId: String,
    val chunkIndex: Int,
    val data: String,
    val mimeType: String = "audio/wav",
    val isLast: Boolean = false,
)

@Serializable
data class MeetingUploadStateDto(
    val meetingId: String,
    val state: MeetingStateEnum,
    val chunkCount: Int,
    val audioSizeBytes: Long,
)

@Serializable
data class MeetingSummaryDto(
    val id: String,
    val title: String? = null,
    val meetingType: MeetingTypeEnum? = null,
    val state: MeetingStateEnum,
    val durationSeconds: Long? = null,
    val startedAt: String,
    val errorMessage: String? = null,
)

@Serializable
data class MergeSuggestionDto(
    val targetMeetingId: String,
    val targetTitle: String?,
    val targetStartedAt: String,
    val targetDurationSeconds: Long?,
)

@Serializable
data class MeetingGroupDto(
    val label: String,
    val periodStart: String,
    val periodEnd: String,
    val count: Int,
)

@Serializable
data class MeetingTimelineDto(
    val currentWeek: List<MeetingSummaryDto>,
    val olderGroups: List<MeetingGroupDto>,
)
