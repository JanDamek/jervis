package com.jervis.service

import com.jervis.dto.meeting.AudioChunkDto
import com.jervis.dto.meeting.CorrectionAnswerDto
import com.jervis.dto.meeting.MeetingCreateDto
import com.jervis.dto.meeting.MeetingDto
import com.jervis.dto.meeting.MeetingFinalizeDto
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

    suspend fun uploadAudioChunk(chunk: AudioChunkDto): Boolean

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
}
