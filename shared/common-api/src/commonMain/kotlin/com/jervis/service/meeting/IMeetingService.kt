package com.jervis.service.meeting

import com.jervis.dto.meeting.AudioChunkDto
import com.jervis.dto.meeting.CorrectionAnswerDto
import com.jervis.dto.meeting.MeetingClassifyDto
import com.jervis.dto.meeting.MeetingCreateDto
import com.jervis.dto.meeting.MeetingDto
import com.jervis.dto.meeting.MeetingMergeDto
import com.jervis.dto.meeting.MeetingFinalizeDto
import com.jervis.dto.meeting.MeetingSummaryDto
import com.jervis.dto.meeting.MeetingTimelineDto
import com.jervis.dto.meeting.MeetingUploadStateDto
import kotlinx.rpc.annotations.Rpc

/**
 * Meeting Recording Service API
 *
 * Manages meeting audio recordings: start/stop recording, upload audio chunks,
 * finalize with metadata, list/get/delete meetings.
 */
@Rpc
interface IMeetingService {

    suspend fun startRecording(request: MeetingCreateDto): MeetingDto

    /** Upload audio chunk. Returns server's current chunkCount after processing. */
    suspend fun uploadAudioChunk(chunk: AudioChunkDto): Int

    suspend fun finalizeRecording(request: MeetingFinalizeDto): MeetingDto

    suspend fun cancelRecording(meetingId: String): Boolean

    suspend fun listMeetings(
        clientId: String,
        projectId: String?,
    ): List<MeetingDto>

    suspend fun getMeeting(meetingId: String): MeetingDto

    suspend fun deleteMeeting(meetingId: String): Boolean

    suspend fun getAudioData(meetingId: String): String

    suspend fun retranscribeMeeting(meetingId: String): Boolean

    suspend fun recorrectMeeting(meetingId: String): Boolean

    suspend fun reindexMeeting(meetingId: String): Boolean

    suspend fun answerCorrectionQuestions(meetingId: String, answers: List<CorrectionAnswerDto>): Boolean

    suspend fun applySegmentCorrection(meetingId: String, segmentIndex: Int, correctedText: String): MeetingDto

    suspend fun correctWithInstruction(meetingId: String, instruction: String): MeetingDto

    suspend fun restoreMeeting(meetingId: String): MeetingDto

    suspend fun permanentlyDeleteMeeting(meetingId: String): Boolean

    suspend fun listDeletedMeetings(clientId: String, projectId: String?): List<MeetingDto>

    suspend fun dismissMeetingError(meetingId: String): Boolean

    suspend fun retranscribeSegments(meetingId: String, segmentIndices: List<Int>): Boolean

    suspend fun stopTranscription(meetingId: String): Boolean

    suspend fun getUploadState(meetingId: String): MeetingUploadStateDto

    suspend fun classifyMeeting(request: MeetingClassifyDto): MeetingDto

    suspend fun updateMeeting(request: MeetingClassifyDto): MeetingDto

    suspend fun listUnclassifiedMeetings(): List<MeetingDto>

    suspend fun getMeetingTimeline(
        clientId: String,
        projectId: String?,
    ): MeetingTimelineDto

    suspend fun mergeMeetings(request: MeetingMergeDto): MeetingDto

    suspend fun listMeetingsByRange(
        clientId: String,
        projectId: String?,
        fromIso: String,
        toIso: String,
    ): List<MeetingSummaryDto>

    /**
     * Link a freshly-created MeetingDocument back to the CALENDAR_PROCESSING
     * task that triggered the recording dispatch (Etapa 2A).
     *
     * Called by the desktop loopback recorder right after `startRecording`
     * succeeds. Writes `meetingMetadata.recordingMeetingId` on the task so
     * downstream code can resolve from a meeting back to its source calendar
     * task without relying on the `deviceSessionId` string heuristic.
     *
     * Returns `true` if the link was applied, `false` if the task no longer
     * exists or carries no `meetingMetadata`.
     */
    suspend fun linkMeetingToTask(taskId: String, meetingId: String): Boolean
}
