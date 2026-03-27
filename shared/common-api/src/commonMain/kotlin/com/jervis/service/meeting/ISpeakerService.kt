package com.jervis.service.meeting

import com.jervis.dto.meeting.SpeakerCreateDto
import com.jervis.dto.meeting.SpeakerDto
import com.jervis.dto.meeting.SpeakerEmbeddingDto
import com.jervis.dto.meeting.SpeakerMappingDto
import com.jervis.dto.meeting.SpeakerMergeRequestDto
import com.jervis.dto.meeting.SpeakerSimilarityDto
import com.jervis.dto.meeting.SpeakerUpdateDto
import com.jervis.dto.meeting.VoiceSampleRefDto
import kotlinx.rpc.annotations.Rpc

@Rpc
interface ISpeakerService {
    suspend fun listSpeakers(clientId: String): List<SpeakerDto>
    suspend fun listAllSpeakers(): List<SpeakerDto>
    suspend fun createSpeaker(request: SpeakerCreateDto): SpeakerDto
    suspend fun updateSpeaker(request: SpeakerUpdateDto): SpeakerDto
    suspend fun deleteSpeaker(speakerId: String): Boolean
    suspend fun assignSpeakers(request: SpeakerMappingDto): Boolean
    suspend fun setVoiceSample(speakerId: String, voiceSample: VoiceSampleRefDto): SpeakerDto
    suspend fun setVoiceEmbedding(request: SpeakerEmbeddingDto): SpeakerDto
    suspend fun checkSimilarity(speakerId1: String, speakerId2: String): SpeakerSimilarityDto
    suspend fun mergeSpeakers(request: SpeakerMergeRequestDto): SpeakerDto
}
