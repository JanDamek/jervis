package com.jervis.rpc

import com.jervis.common.types.ClientId
import com.jervis.dto.meeting.SpeakerChannelDto
import com.jervis.dto.meeting.SpeakerCreateDto
import com.jervis.dto.meeting.SpeakerDto
import com.jervis.dto.meeting.SpeakerEmbeddingDto
import com.jervis.dto.meeting.SpeakerMappingDto
import com.jervis.dto.meeting.SpeakerMergeRequestDto
import com.jervis.dto.meeting.SpeakerSimilarityDto
import com.jervis.dto.meeting.SpeakerUpdateDto
import com.jervis.dto.meeting.VoiceSampleRefDto
import com.jervis.entity.SpeakerChannelEntry
import com.jervis.entity.SpeakerDocument
import com.jervis.entity.VoiceEmbeddingEntry
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
        speakerRepository.findByClientIdsContainingOrderByNameAsc(ClientId.fromString(clientId)).toList().map { it.toDto() }

    override suspend fun listAllSpeakers(): List<SpeakerDto> =
        speakerRepository.findAllByOrderByNameAsc().toList().map { it.toDto() }

    override suspend fun createSpeaker(request: SpeakerCreateDto): SpeakerDto {
        val doc = SpeakerDocument(
            clientIds = request.clientIds.map { ClientId.fromString(it) },
            name = request.name,
            nationality = request.nationality,
            languagesSpoken = request.languagesSpoken,
            notes = request.notes,
            emails = request.emails.map { it.lowercase().trim() }.distinct(),
            channels = request.channels.map { it.toEntry() },
        )
        val saved = speakerRepository.save(doc)
        logger.info { "Created speaker: ${saved.id} name=${saved.name} clients=${saved.clientIds}" }
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
            clientIds = request.clientIds.map { ClientId.fromString(it) },
            emails = request.emails.map { it.lowercase().trim() }.distinct(),
            channels = request.channels.map { it.toEntry() },
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

    override suspend fun checkSimilarity(speakerId1: String, speakerId2: String): SpeakerSimilarityDto {
        val s1 = speakerRepository.findById(ObjectId(speakerId1)) ?: error("Speaker not found: $speakerId1")
        val s2 = speakerRepository.findById(ObjectId(speakerId2)) ?: error("Speaker not found: $speakerId2")
        val emb1 = s1.allEmbeddings()
        val emb2 = s2.allEmbeddings()
        if (emb1.isEmpty() || emb2.isEmpty()) {
            return SpeakerSimilarityDto(speakerId1, speakerId2, similarity = -1f)
        }
        var bestSim = 0f
        for (e1 in emb1) {
            for (e2 in emb2) {
                val sim = cosineSimilarity(e1.embedding, e2.embedding)
                if (sim > bestSim) bestSim = sim
            }
        }
        return SpeakerSimilarityDto(speakerId1, speakerId2, similarity = bestSim)
    }

    override suspend fun mergeSpeakers(request: SpeakerMergeRequestDto): SpeakerDto {
        val targetId = ObjectId(request.targetSpeakerId)
        val sourceId = ObjectId(request.sourceSpeakerId)
        val target = speakerRepository.findById(targetId) ?: error("Target speaker not found: ${request.targetSpeakerId}")
        val source = speakerRepository.findById(sourceId) ?: error("Source speaker not found: ${request.sourceSpeakerId}")

        val merged = target.copy(
            clientIds = (target.clientIds + source.clientIds).distinct(),
            emails = (target.emails + source.emails).map { it.lowercase().trim() }.distinct(),
            channels = (target.channels + source.channels).distinctBy { it.connectionId to it.identifier },
            voiceEmbeddings = target.voiceEmbeddings + source.voiceEmbeddings,
            languagesSpoken = (target.languagesSpoken + source.languagesSpoken).distinct(),
            notes = listOfNotNull(target.notes, source.notes).joinToString("\n").ifBlank { null },
            nationality = target.nationality ?: source.nationality,
            updatedAt = Instant.now(),
        )
        val saved = speakerRepository.save(merged)

        // Update meeting speaker mappings that reference the source speaker
        val sourceIdStr = source.id.toHexString()
        val targetIdStr = target.id.toHexString()
        for (clientId in source.clientIds) {
            meetingRepository.findByClientIdAndDeletedIsFalseOrderByStartedAtDesc(clientId).collect { meeting ->
                if (sourceIdStr in meeting.speakerMapping.values) {
                    val updatedMapping = meeting.speakerMapping.mapValues { (_, v) ->
                        if (v == sourceIdStr) targetIdStr else v
                    }
                    meetingRepository.save(meeting.copy(speakerMapping = updatedMapping))
                }
            }
        }

        speakerRepository.deleteById(sourceId)
        logger.info { "Merged speaker ${source.name} (${source.id}) into ${target.name} (${target.id})" }
        return saved.toDto()
    }

    override suspend fun setVoiceEmbedding(request: SpeakerEmbeddingDto): SpeakerDto {
        val id = ObjectId(request.speakerId)
        val existing = speakerRepository.findById(id) ?: error("Speaker not found: ${request.speakerId}")
        require(request.embedding.size == 256) { "Voice embedding must be 256-dimensional, got ${request.embedding.size}" }

        val entry = VoiceEmbeddingEntry(
            embedding = request.embedding,
            label = request.label,
            meetingId = request.meetingId?.let { ObjectId(it) },
        )

        val updated = existing.copy(
            voiceEmbeddings = existing.voiceEmbeddings + entry,
            voiceEmbedding = null,
            updatedAt = Instant.now(),
        )
        val saved = speakerRepository.save(updated)
        logger.info { "Added voice embedding for speaker ${request.speakerId} (${saved.name}), total: ${saved.voiceEmbeddings.size}" }
        return saved.toDto()
    }
}

private fun SpeakerDocument.toDto(): SpeakerDto {
    val allEmb = allEmbeddings()
    return SpeakerDto(
        id = id.toHexString(),
        clientIds = clientIds.map { it.toString() },
        name = name,
        nationality = nationality,
        languagesSpoken = languagesSpoken,
        notes = notes,
        emails = emails,
        channels = channels.map { it.toDto() },
        voiceSampleRef = voiceSampleRef?.let {
            VoiceSampleRefDto(
                meetingId = it.meetingId.toHexString(),
                startSec = it.startSec,
                endSec = it.endSec,
            )
        },
        hasVoiceprint = allEmb.isNotEmpty(),
        voiceprintCount = allEmb.size,
        voiceprintLabels = allEmb.mapNotNull { it.label },
        createdAt = createdAt.toString(),
        updatedAt = updatedAt.toString(),
    )
}

private fun SpeakerChannelDto.toEntry() = SpeakerChannelEntry(
    connectionId = connectionId,
    identifier = identifier,
    displayName = displayName,
)

private fun SpeakerChannelEntry.toDto() = SpeakerChannelDto(
    connectionId = connectionId,
    identifier = identifier,
    displayName = displayName,
)

private fun cosineSimilarity(a: List<Float>, b: List<Float>): Float {
    if (a.size != b.size) return 0f
    var dot = 0f
    var normA = 0f
    var normB = 0f
    for (i in a.indices) {
        dot += a[i] * b[i]
        normA += a[i] * a[i]
        normB += b[i] * b[i]
    }
    val denom = kotlin.math.sqrt(normA) * kotlin.math.sqrt(normB)
    return if (denom > 0f) dot / denom else 0f
}
