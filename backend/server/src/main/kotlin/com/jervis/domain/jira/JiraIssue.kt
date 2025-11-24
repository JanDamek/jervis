package com.jervis.domain.jira

import kotlinx.serialization.Serializable
import java.time.Instant

/**
 * Jira issue domain model.
 */
@Serializable
data class JiraIssue(
    val id: String,
    val key: String,
    val summary: String,
    @Serializable(with = InstantSerializer::class)
    val updated: Instant,
    val issueType: String,
    val status: String,
)

// Instant serializer for kotlinx.serialization
private object InstantSerializer : kotlinx.serialization.KSerializer<Instant> {
    override val descriptor = kotlinx.serialization.descriptors.PrimitiveSerialDescriptor("Instant", kotlinx.serialization.descriptors.PrimitiveKind.STRING)
    override fun serialize(encoder: kotlinx.serialization.encoding.Encoder, value: Instant) = encoder.encodeString(value.toString())
    override fun deserialize(decoder: kotlinx.serialization.encoding.Decoder): Instant = Instant.parse(decoder.decodeString())
}
