package com.jervis.rpc

import com.jervis.common.types.ClientId
import com.jervis.dto.meeting.SpeakerCreateDto
import com.jervis.dto.meeting.SpeakerDto
import com.jervis.dto.meeting.SpeakerEmbeddingDto
import com.jervis.dto.meeting.SpeakerMappingDto
import com.jervis.dto.meeting.SpeakerUpdateDto
import com.jervis.dto.meeting.VoiceSampleRefDto
import com.jervis.entity.SpeakerDocument
import com.jervis.entity.VoiceSampleRef
import com.jervis.repository.MeetingRepository
import com.jervis.repository.SpeakerRepository
import com.jervis.service.ISpeakerService
import kotlinx.coroutines.flow.toList
import mu.KotlinLogging
import org.bson.types.ObjectId
import org.springframework.stereotype.Component
import java.time.Instant

private val logger = KotlinLogging.logger {}

@Component
class SpeakerRpcImpl(
    private val speakerRepository: SpeakerRepository,
    private val meetingRepository: MeetingRepository,
) : ISpeakerService {

    override suspend fun listSpeakers(clientId: String): List<SpeakerDto> =
        speakerRepository.findByClientIdOrderByNameAsc(ClientId.fromString(clientId)).toList().map { it.toDto() }

    override suspend fun createSpeaker(request: SpeakerCreateDto): SpeakerDto {
        val doc = SpeakerDocument(
            clientId = ClientId.fromString(request.clientId),
            name = request.name,
            nationality = request.nationality,
            languagesSpoken = request.languagesSpoken,
            notes = request.notes,
        )
        val saved = speakerRepository.save(doc)
        logger.info { "Created speaker: ${saved.id} name=${saved.name} client=${saved.clientId}" }
        return saved.toDto()
    }

    override suspend fun updateSpeaker(request: SpeakerUpdateDto): SpeakerDto {
        val id = ObjectId(request.id)
        val existing = speakerRepository.findById(id) ?: error("Speaker not found: ${request.id}")
        val updated = existing.copy(
            name = request.name,
            nationality = request.nationality,
            languagesSpoken = request.languagesSpoken,
            notes = request.notes,
            updatedAt = Instant.now(),
        )
        val saved = speakerRepository.save(updated)
        logger.info { "Updated speaker: ${saved.id} name=${saved.name}" }
        return saved.toDto()
    }

    override suspend fun deleteSpeaker(speakerId: String): Boolean {
        val id = ObjectId(speakerId)
        speakerRepository.deleteById(id)
        logger.info { "Deleted speaker: $speakerId" }
        return true
    }

    override suspend fun assignSpeakers(request: SpeakerMappingDto): Boolean {
        val meetingId = ObjectId(request.meetingId)
        val meeting = meetingRepository.findById(meetingId) ?: error("Meeting not found: ${request.meetingId}")
        val updated = meeting.copy(speakerMapping = request.speakerMapping)
        meetingRepository.save(updated)
        logger.info { "Assigned speakers for meeting ${request.meetingId}: ${request.speakerMapping}" }
        return true
    }

    override suspend fun setVoiceSample(speakerId: String, voiceSample: VoiceSampleRefDto): SpeakerDto {
        val id = ObjectId(speakerId)
        val existing = speakerRepository.findById(id) ?: error("Speaker not found: $speakerId")
        val updated = existing.copy(
            voiceSampleRef = VoiceSampleRef(
                meetingId = ObjectId(voiceSample.meetingId),
                startSec = voiceSample.startSec,
                endSec = voiceSample.endSec,
            ),
            updatedAt = Instant.now(),
        )
        val saved = speakerRepository.save(updated)
        logger.info { "Set voice sample for speaker $speakerId: meeting=${voiceSample.meetingId} ${voiceSample.startSec}-${voiceSample.endSec}s" }
        return saved.toDto()
    }

    override suspend fun setVoiceEmbedding(request: SpeakerEmbeddingDto): SpeakerDto {
        val id = ObjectId(request.speakerId)
        val existing = speakerRepository.findById(id) ?: error("Speaker not found: ${request.speakerId}")
        require(request.embedding.size == 256) { "Voice embedding must be 256-dimensional, got ${request.embedding.size}" }
        val updated = existing.copy(
            voiceEmbedding = request.embedding,
            updatedAt = Instant.now(),
        )
        val saved = speakerRepository.save(updated)
        logger.info { "Set voice embedding for speaker ${request.speakerId} (${saved.name})" }
        return saved.toDto()
    }
}

private fun SpeakerDocument.toDto(): SpeakerDto =
    SpeakerDto(
        id = id.toHexString(),
        clientId = clientId.toString(),
        name = name,
        nationality = nationality,
        languagesSpoken = languagesSpoken,
        notes = notes,
        voiceSampleRef = voiceSampleRef?.let {
            VoiceSampleRefDto(
                meetingId = it.meetingId.toHexString(),
                startSec = it.startSec,
                endSec = it.endSec,
            )
        },
        hasVoiceprint = voiceEmbedding != null,
        createdAt = createdAt.toString(),
        updatedAt = updatedAt.toString(),
    )
