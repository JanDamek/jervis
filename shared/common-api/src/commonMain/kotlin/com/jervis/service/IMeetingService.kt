package com.jervis.service

import com.jervis.dto.meeting.AudioChunkDto
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
}
