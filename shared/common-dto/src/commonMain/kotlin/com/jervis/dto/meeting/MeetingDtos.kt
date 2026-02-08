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
    val errorMessage: String? = null,
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
    val mimeType: String = "audio/webm",
    val isLast: Boolean = false,
)
