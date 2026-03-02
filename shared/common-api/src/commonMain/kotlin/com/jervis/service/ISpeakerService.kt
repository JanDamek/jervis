package com.jervis.service

import com.jervis.dto.meeting.SpeakerCreateDto
import com.jervis.dto.meeting.SpeakerDto
import com.jervis.dto.meeting.SpeakerMappingDto
import com.jervis.dto.meeting.SpeakerUpdateDto
import com.jervis.dto.meeting.VoiceSampleRefDto
import kotlinx.rpc.annotations.Rpc

@Rpc
interface ISpeakerService {
    suspend fun listSpeakers(clientId: String): List<SpeakerDto>
    suspend fun createSpeaker(request: SpeakerCreateDto): SpeakerDto
    suspend fun updateSpeaker(request: SpeakerUpdateDto): SpeakerDto
    suspend fun deleteSpeaker(speakerId: String): Boolean
    suspend fun assignSpeakers(request: SpeakerMappingDto): Boolean
    suspend fun setVoiceSample(speakerId: String, voiceSample: VoiceSampleRefDto): SpeakerDto
}
