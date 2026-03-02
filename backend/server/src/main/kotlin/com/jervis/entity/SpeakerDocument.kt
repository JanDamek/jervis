package com.jervis.entity

import com.jervis.common.types.ClientId
import org.bson.types.ObjectId
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.index.CompoundIndex
import org.springframework.data.mongodb.core.mapping.Document
import java.time.Instant

@Document(collection = "speakers")
@CompoundIndex(name = "client_name_idx", def = "{'clientId': 1, 'name': 1}")
data class SpeakerDocument(
    @Id
    val id: ObjectId = ObjectId.get(),
    val clientId: ClientId,
    val name: String,
    val nationality: String? = null,
    val languagesSpoken: List<String> = emptyList(),
    val notes: String? = null,
    val voiceSampleRef: VoiceSampleRef? = null,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now(),
)

data class VoiceSampleRef(
    val meetingId: ObjectId,
    val startSec: Double,
    val endSec: Double,
)
