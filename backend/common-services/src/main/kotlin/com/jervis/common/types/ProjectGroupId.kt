package com.jervis.common.types

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import org.bson.types.ObjectId

@Serializable(with = ProjectGroupIdSerializer::class)
@JvmInline
value class ProjectGroupId(
    val value: ObjectId,
) {
    override fun toString(): String = value.toHexString()

    companion object {
        fun fromString(hex: String): ProjectGroupId = ProjectGroupId(ObjectId(hex))

        fun generate(): ProjectGroupId = ProjectGroupId(ObjectId())
    }
}

object ProjectGroupIdSerializer : KSerializer<ProjectGroupId> {
    override val descriptor = PrimitiveSerialDescriptor("ProjectGroupId", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: ProjectGroupId) = encoder.encodeString(value.value.toHexString())
    override fun deserialize(decoder: Decoder): ProjectGroupId = ProjectGroupId(ObjectId(decoder.decodeString()))
}
