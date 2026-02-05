package com.jervis.common.types

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import org.bson.types.ObjectId

@Serializable(with = ProjectIdSerializer::class)
@JvmInline
value class ProjectId(
    val value: ObjectId,
) {
    override fun toString(): String = value.toHexString()

    companion object {
        fun fromString(hex: String): ProjectId = ProjectId(ObjectId(hex))

        fun generate(): ProjectId = ProjectId(ObjectId())
    }
}

object ProjectIdSerializer : KSerializer<ProjectId> {
    override val descriptor = PrimitiveSerialDescriptor("ProjectId", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: ProjectId) = encoder.encodeString(value.value.toHexString())
    override fun deserialize(decoder: Decoder): ProjectId = ProjectId(ObjectId(decoder.decodeString()))
}
